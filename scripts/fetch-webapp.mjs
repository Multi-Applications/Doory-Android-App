#!/usr/bin/env node
import { mkdir, writeFile, stat } from "node:fs/promises";
import { dirname, join, extname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));
const OUT = join(ROOT, "app", "src", "main", "assets", "www");
const ORIGIN = "https://doory.art";
const MAX_FILES = 4000;
const CONCURRENCY = 12;

const seeds = [
  "/",
  "/manifest.webmanifest",
  "/icons/favicon-32.png",
  "/icons/apple-touch-icon.png",
  "/icons/icon-192.png",
  "/icons/icon-512.png",
  "/icons/icon-maskable-512.png",
  "/og-image.png",
  "/favicon.ico",
];

const seen = new Map();
const enqueued = new Set(seeds);
const queue = [...seeds];
let active = 0;
let failed = 0;
let bytes = 0;

const assetExt =
  /\.(?:js|mjs|css|woff2?|ttf|otf|eot|png|jpe?g|gif|svg|webp|avif|ico|wasm|json|mp4|webm|mp3|wav|txt|webmanifest|map)$/i;

function collect(text, basePath, contentType) {
  const refs = new Set();

  if (contentType.includes("html")) {
    for (const m of text.matchAll(/(?:src|href)=["']([^"']+)["']/gi)) refs.add(m[1]);
    for (const m of text.matchAll(/url\((["']?)([^"')]+)\1\)/gi)) refs.add(m[2]);
    for (const m of text.matchAll(/["'](\/assets\/[^"']+)["']/g)) refs.add(m[1]);
  } else if (contentType.includes("javascript") || /\.m?js$/i.test(basePath)) {
    for (const m of text.matchAll(/["'`]((?:\.{1,2}\/|\/|assets\/)[^"'`\s()]+)["'`]/g)) {
      const v = m[1];
      if (assetExt.test(v) || v.includes("/assets/")) refs.add(v);
    }
    for (const m of text.matchAll(/["'](assets\/[^"']+)["']/g)) refs.add(m[1]);
  } else if (contentType.includes("css") || /\.css$/i.test(basePath)) {
    for (const m of text.matchAll(/url\((["']?)([^"')]+)\1\)/gi)) refs.add(m[2]);
    for (const m of text.matchAll(/@import\s+["']([^"']+)["']/gi)) refs.add(m[1]);
  } else if (/\.webmanifest$/i.test(basePath) || /\.json$/i.test(basePath)) {
    try {
      const walk = (v) => {
        if (typeof v === "string" && /^(\/|https?:\/\/)/.test(v)) refs.add(v);
        else if (Array.isArray(v)) v.forEach(walk);
        else if (v && typeof v === "object") Object.values(v).forEach(walk);
      };
      walk(JSON.parse(text));
    } catch {}
  }

  for (const original of refs) {
    let raw = original;
    if (!raw || raw.startsWith("data:") || raw.startsWith("blob:") || raw.startsWith("#")) continue;
    if (raw.includes("${") || raw.includes("$%7B")) continue;
    if (raw.startsWith("assets/")) raw = "/" + raw;
    let url;
    try {
      url = new URL(raw, ORIGIN + basePath);
    } catch {
      continue;
    }
    if (url.origin !== ORIGIN) continue;
    if (!assetExt.test(url.pathname) && !url.pathname.startsWith("/assets/")) continue;
    if (url.pathname === "/" || url.pathname.endsWith("/")) continue;
    if (url.pathname.startsWith("/assets/") && !assetExt.test(url.pathname)) continue;
    if (!enqueued.has(url.pathname)) {
      enqueued.add(url.pathname);
      queue.push(url.pathname);
    }
  }
}

async function worker() {
  while (queue.length) {
    const pathname = queue.shift();
    if (seen.has(pathname)) continue;
    let res;
    try {
      res = await fetch(ORIGIN + pathname, {
        headers: { "user-agent": "DooryAndroidPackager/1.0 (+offline mirror)" },
      });
    } catch (err) {
      console.warn("FETCH FAIL", pathname, err.message);
      failed++;
      seen.set(pathname, { size: 0, type: "error" });
      continue;
    }
    if (!res.ok) {
      console.warn("HTTP", res.status, pathname);
      failed++;
      seen.set(pathname, { size: 0, type: "error" });
      continue;
    }
    const buf = Buffer.from(await res.arrayBuffer());
    const type = res.headers.get("content-type") || "";
    const relative = pathname === "/" ? "index.html" : decodeURIComponent(pathname).replace(/^\/+/, "");
    const filePath = join(OUT, relative);
    await mkdir(dirname(filePath), { recursive: true });
    await writeFile(filePath, buf);
    bytes += buf.length;
    seen.set(pathname, { size: buf.length, type });
    const texty = /text|javascript|json|css|svg|xml|webmanifest/i.test(type) || /\.(js|css|html|json|svg|webmanifest)$/i.test(pathname);
    if (texty && buf.length < 12 * 1024 * 1024) collect(buf.toString("utf8"), pathname, type);
    if (seen.size % 50 === 0) console.log(`… ${seen.size} files, ${(bytes / 1048576).toFixed(1)} MB`);
    if (seen.size >= MAX_FILES) {
      console.warn("MAX_FILES reached");
      queue.length = 0;
    }
  }
}

await mkdir(OUT, { recursive: true });
console.log("Mirroring", ORIGIN, "→", OUT);
await Promise.all(Array.from({ length: CONCURRENCY }, worker));

const meta = {
  origin: ORIGIN,
  fetchedAt: new Date().toISOString(),
  files: [...seen.entries()].filter(([, v]) => v.size > 0).length,
  bytes,
  failures: failed,
};
await writeFile(join(ROOT, "app", "src", "main", "assets", "www", "offline-snapshot.json"), JSON.stringify(meta, null, 2));

const failedPaths = [...seen.entries()].filter(([, v]) => v.type === "error").map(([p]) => p);
console.log(`\nDone: ${meta.files} files, ${(bytes / 1048576).toFixed(2)} MB, ${failed} failed`);
if (failedPaths.length) console.log("Failed paths:\n" + failedPaths.join("\n"));
console.log("Snapshot:", meta);