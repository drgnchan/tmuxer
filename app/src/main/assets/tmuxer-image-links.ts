import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Text } from "@earendil-works/pi-tui";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, renameSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const MAX_IMAGE_BYTES = 18 * 1024 * 1024;
const ENTRY_TYPE = "tmuxer-image-link";
const imageDirectory = process.env.TMUXER_IMAGE_DIR;

function imageExtension(mimeType: string): string {
  if (mimeType === "image/png") return "png";
  if (mimeType === "image/jpeg") return "jpg";
  if (mimeType === "image/gif") return "gif";
  if (mimeType === "image/webp") return "webp";
  return "bin";
}

function saveImage(data: string, mimeType: string): string | undefined {
  if (!imageDirectory) return undefined;
  const bytes = Buffer.from(data, "base64");
  if (bytes.length === 0 || bytes.length > MAX_IMAGE_BYTES) return undefined;
  mkdirSync(imageDirectory, { recursive: true, mode: 0o700 });
  const hash = createHash("sha256").update(bytes).digest("hex");
  const remotePath = join(imageDirectory, `${hash}.${imageExtension(mimeType)}`);
  if (!existsSync(remotePath)) {
    const temporaryPath = `${remotePath}.${process.pid}.tmp`;
    writeFileSync(temporaryPath, bytes, { mode: 0o600 });
    renameSync(temporaryPath, remotePath);
  }
  return remotePath;
}

function imageHyperlink(remotePath: string): string {
  const encodedPath = Buffer.from(remotePath, "utf8").toString("base64url");
  return `\x1b]8;;tmuxer-image://${encodedPath}\x1b\\图片 · 点击预览\x1b]8;;\x1b\\`;
}

export default function tmuxerImageLinks(pi: ExtensionAPI) {
  pi.registerEntryRenderer(ENTRY_TYPE, (entry, _options, theme) => {
    const remotePath = (entry.data as { remotePath?: unknown } | undefined)?.remotePath;
    if (typeof remotePath !== "string" || !remotePath.startsWith("/")) {
      return new Text(theme.fg("warning", "图片链接已失效"), 0, 0);
    }
    return new Text(theme.fg("accent", imageHyperlink(remotePath)), 0, 0);
  });

  pi.on("tool_result", (event) => {
    for (const block of event.content) {
      if (block.type !== "image" || !block.data || !block.mimeType) continue;
      const remotePath = saveImage(block.data, block.mimeType);
      if (remotePath) {
        pi.appendEntry(ENTRY_TYPE, { remotePath, mimeType: block.mimeType });
      }
    }
  });
}
