// @ts-ignore - URL imports are resolved by the Supabase Edge runtime.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

declare const Deno: {
  env: { get(key: string): string | undefined };
  serve(handler: (req: Request) => Promise<Response> | Response): void;
};

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "Method not allowed" }), {
      status: 405,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }

  try {
    const body = await req.json();
    const action = String(body.action ?? "upload").trim().toLowerCase();
    const imageBase64 = String(body.imageBase64 ?? "").trim();
    const imageUrl = String(body.imageUrl ?? "").trim();

    if (action !== "upload" && action !== "delete") {
      return new Response(JSON.stringify({ error: "Invalid action. Supported values: upload, delete" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    if (action === "upload" && !imageBase64) {
      return new Response(JSON.stringify({ error: "Missing field: imageBase64" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    if (action === "delete" && !imageUrl) {
      return new Response(JSON.stringify({ error: "Missing field: imageUrl" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const serviceRoleKey = Deno.env.get("APP_SUPABASE_SERVICE_ROLE_KEY");
    const supabaseUrl = Deno.env.get("APP_SUPABASE_URL");

    if (!serviceRoleKey || !supabaseUrl) {
      return new Response(JSON.stringify({ error: "Missing APP_SUPABASE_URL or APP_SUPABASE_SERVICE_ROLE_KEY secret" }), {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const supabase = createClient(supabaseUrl, serviceRoleKey);

    const bucket = "cleave_group_images";

    if (action === "delete") {
      const objectPath = extractObjectPathFromImageUrl(imageUrl, bucket);
      if (!objectPath) {
        return new Response(JSON.stringify({ error: "Could not resolve object path from imageUrl" }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      const { error: deleteError } = await supabase.storage
        .from(bucket)
        .remove([objectPath]);

      if (deleteError) {
        return new Response(JSON.stringify({ error: deleteError.message }), {
          status: 500,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      return new Response(JSON.stringify({ deleted: true }), {
        status: 200,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const objectPath = `groups/${crypto.randomUUID()}.jpg`;

    const bytes = Uint8Array.from(atob(imageBase64), (c) => c.charCodeAt(0));

    const { error: uploadError } = await supabase.storage
      .from(bucket)
      .upload(objectPath, bytes, {
        contentType: "image/jpeg",
        upsert: true,
      });

    if (uploadError) {
      return new Response(JSON.stringify({ error: uploadError.message }), {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const tenYears = 60 * 60 * 24 * 365 * 10;
    const { data, error: signedError } = await supabase.storage
      .from(bucket)
      .createSignedUrl(objectPath, tenYears);

    if (signedError || !data?.signedUrl) {
      return new Response(JSON.stringify({ error: signedError?.message ?? "Could not create signed URL" }), {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    return new Response(JSON.stringify({ imageUrl: data.signedUrl }), {
      status: 200,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (error) {
    return new Response(JSON.stringify({ error: String(error) }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});

function extractObjectPathFromImageUrl(imageUrl: string, bucket: string): string | null {
  try {
    const parsed = new URL(imageUrl);
    const pathname = parsed.pathname;
    const signPrefix = `/storage/v1/object/sign/${bucket}/`;
    const publicPrefix = `/storage/v1/object/public/${bucket}/`;

    let rawPath: string | null = null;
    if (pathname.includes(signPrefix)) {
      rawPath = pathname.split(signPrefix)[1] ?? null;
    } else if (pathname.includes(publicPrefix)) {
      rawPath = pathname.split(publicPrefix)[1] ?? null;
    }

    const normalized = decodeURIComponent((rawPath ?? "").trim()).replace(/^\/+/, "");
    if (!normalized) return null;
    return normalized;
  } catch {
    return null;
  }
}
