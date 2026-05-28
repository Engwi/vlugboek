import crypto from "node:crypto";
import express from "express";
import dotenv from "dotenv";
import helmet from "helmet";
import nodemailer from "nodemailer";
import { z } from "zod";

dotenv.config({ override: true });

const {
  VLUGBOEK_MAILER_PORT,
  PORT = "8788",
  HOST = "127.0.0.1",
  NODE_ENV = "development",
  MAIL_JSON_LIMIT = "50mb",
  MAIL_WEBHOOK_TOKEN,
  MAIL_FROM_NAME = "Vlugboek",
  MAIL_FROM_ADDRESS,
  GMAIL_USER,
  GMAIL_APP_PASSWORD,
  SMTP_USER,
  SMTP_PASSWORD,
  SMTP_HOST = "smtp.gmail.com",
  SMTP_PORT = "587",
  SMTP_SECURE = "false",
  SMTP_CONNECTION_TIMEOUT = "10000",
  SMTP_GREETING_TIMEOUT = "10000",
  SMTP_SOCKET_TIMEOUT = "20000",
  ALLOWED_TO_DOMAIN = ""
} = process.env;

const LISTEN_PORT = VLUGBOEK_MAILER_PORT || PORT || "8788";
const SMTP_PORT_NUMBER = Number(SMTP_PORT);
const SMTP_IS_SECURE = SMTP_SECURE.toLowerCase() === "true" || SMTP_PORT_NUMBER === 465;
const SMTP_AUTH_USER = SMTP_USER || GMAIL_USER;
const SMTP_AUTH_PASSWORD = SMTP_PASSWORD || GMAIL_APP_PASSWORD;
const FROM_ADDRESS = MAIL_FROM_ADDRESS || SMTP_AUTH_USER;

if (!MAIL_WEBHOOK_TOKEN) throw new Error("Missing MAIL_WEBHOOK_TOKEN");
if (!SMTP_AUTH_USER) throw new Error("Missing SMTP_USER or GMAIL_USER");
if (!SMTP_AUTH_PASSWORD) throw new Error("Missing SMTP_PASSWORD or GMAIL_APP_PASSWORD");
if (!Number.isInteger(SMTP_PORT_NUMBER) || SMTP_PORT_NUMBER <= 0) throw new Error("SMTP_PORT must be a valid port number");

const app = express();
app.use(helmet({ contentSecurityPolicy: false }));
app.use(express.json({ limit: MAIL_JSON_LIMIT }));

const transporter = nodemailer.createTransport({
  host: SMTP_HOST,
  port: SMTP_PORT_NUMBER,
  secure: SMTP_IS_SECURE,
  requireTLS: !SMTP_IS_SECURE,
  connectionTimeout: Number(SMTP_CONNECTION_TIMEOUT),
  greetingTimeout: Number(SMTP_GREETING_TIMEOUT),
  socketTimeout: Number(SMTP_SOCKET_TIMEOUT),
  auth: {
    user: SMTP_AUTH_USER,
    pass: SMTP_AUTH_PASSWORD
  },
  tls: {
    servername: SMTP_HOST
  }
});

const attachmentSchema = z.object({
  filename: z.string().min(1).max(240),
  contentType: z.string().min(1).max(120),
  contentBase64: z.string().min(1)
});

const payloadSchema = z.object({
  to: z.string().email(),
  subject: z.string().min(1).max(200),
  text: z.string().min(1).max(20000),
  html: z.string().min(1).max(50000).optional(),
  attachments: z.array(attachmentSchema).min(1).max(5),
  requestId: z.string().min(1).max(120).optional(),
  meta: z.record(z.any()).optional()
});

const messagePayloadSchema = z.object({
  to: z.string().email(),
  subject: z.string().min(1).max(200),
  text: z.string().min(1).max(20000),
  html: z.string().min(1).max(50000).optional(),
  requestId: z.string().min(1).max(120).optional(),
  meta: z.record(z.any()).optional()
});

function safeEqual(a, b) {
  const ab = Buffer.from(a || "");
  const bb = Buffer.from(b || "");
  if (ab.length !== bb.length) return false;
  return crypto.timingSafeEqual(ab, bb);
}

function requireAuth(req, res, next) {
  const auth = req.headers.authorization || "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
  if (!safeEqual(token, MAIL_WEBHOOK_TOKEN)) {
    return res.status(401).json({ error: "Unauthorized" });
  }
  next();
}

function assertAllowedRecipient(to) {
  if (!ALLOWED_TO_DOMAIN) return;
  const domain = to.split("@")[1]?.toLowerCase();
  if (domain !== ALLOWED_TO_DOMAIN.toLowerCase()) {
    const error = new Error("Recipient domain blocked");
    error.status = 403;
    throw error;
  }
}

function health(_req, res) {
  res.status(200).json({
    ok: true,
    service: "vlugboek-mailer",
    smtp: {
      host: SMTP_HOST,
      port: SMTP_PORT_NUMBER,
      secure: SMTP_IS_SECURE
    },
    env: NODE_ENV
  });
}

app.get("/health", health);
app.get("/healthz", health);

function requestIdFor(req, data) {
  return data.requestId || req.headers["x-vlugboek-request-id"] || crypto.randomUUID();
}

app.get("/ready", requireAuth, async (_req, res) => {
  try {
    await transporter.verify();
    res.status(200).json({ ok: true, service: "vlugboek-mailer", smtp: "ready" });
  } catch (err) {
    res.status(502).json({ ok: false, service: "vlugboek-mailer", error: err.message });
  }
});

app.post("/send-document", requireAuth, async (req, res) => {
  const parsed = payloadSchema.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({ error: "Invalid payload", details: parsed.error.flatten() });
  }

  const data = parsed.data;
  const requestId = requestIdFor(req, data);

  try {
    assertAllowedRecipient(data.to);

    const info = await transporter.sendMail({
      from: `"${MAIL_FROM_NAME}" <${FROM_ADDRESS}>`,
      to: data.to,
      subject: data.subject,
      text: data.text,
      html: data.html,
      attachments: data.attachments.map((attachment) => ({
        filename: attachment.filename,
        contentType: attachment.contentType,
        content: Buffer.from(attachment.contentBase64, "base64")
      })),
      headers: {
        "X-Vlugboek-Source": "document-email",
        "X-Vlugboek-Request-Id": requestId
      }
    });

    return res.status(200).json({ ok: true, messageId: info.messageId, requestId });
  } catch (err) {
    const status = err.status || 502;
    const detail = err.code ? `${err.code}: ${err.message}` : err.message;
    console.error("send-document failed:", {
      requestId,
      status,
      message: err.message,
      code: err.code,
      meta: data.meta
    });
    return res.status(status).json({
      error: status === 403 ? err.message : "Mail provider send failed",
      detail
    });
  }
});

app.post("/send-message", requireAuth, async (req, res) => {
  const parsed = messagePayloadSchema.safeParse(req.body);
  if (!parsed.success) {
    return res.status(400).json({ error: "Invalid payload", details: parsed.error.flatten() });
  }

  const data = parsed.data;
  const requestId = requestIdFor(req, data);

  try {
    assertAllowedRecipient(data.to);

    const info = await transporter.sendMail({
      from: `"${MAIL_FROM_NAME}" <${FROM_ADDRESS}>`,
      to: data.to,
      subject: data.subject,
      text: data.text,
      html: data.html,
      headers: {
        "X-Vlugboek-Source": "message-email",
        "X-Vlugboek-Request-Id": requestId
      }
    });

    return res.status(200).json({ ok: true, messageId: info.messageId, requestId });
  } catch (err) {
    const status = err.status || 502;
    const detail = err.code ? `${err.code}: ${err.message}` : err.message;
    console.error("send-message failed:", {
      requestId,
      status,
      message: err.message,
      code: err.code,
      meta: data.meta
    });
    return res.status(status).json({
      error: status === 403 ? err.message : "Mail provider send failed",
      detail
    });
  }
});

const server = app.listen(Number(LISTEN_PORT), HOST, () => {
  console.log(`vlugboek-mailer listening on http://${HOST}:${LISTEN_PORT}`);
  if (NODE_ENV !== "production") {
    console.log("Running in non-production mode");
  }
});

server.on("error", (err) => {
  if (err.code === "EADDRINUSE") {
    console.error(`vlugboek-mailer could not start because ${HOST}:${LISTEN_PORT} is already in use.`);
  } else {
    console.error("vlugboek-mailer failed to start:", err);
  }
  process.exit(1);
});
