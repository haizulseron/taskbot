package com.haizul.taskbot;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Draft;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GmailService {

    // ── Standard email signature ──────────────────────────────────────────────
    // Always appended to every outgoing draft/reply.
    private static final String SIGNATURE = """


--
Warm regards,
Haizul Ali Seron""";

    private final Gmail gmail;

    public GmailService(GoogleAuthService auth) throws Exception {
        this.gmail = new Gmail.Builder(
                auth.getHttpTransport(),
                auth.getJsonFactory(),
                auth.getHttpCredentials())
                .setApplicationName("TaskBot")
                .build();
    }

    // ── Records ───────────────────────────────────────────────────────────────

    public record EmailSummary(String id, String from, String subject, String date, String snippet) {}
    public record EmailContent(String id, String from, String subject, String date, String body, String threadId) {}
    public record Attachment(String filename, byte[] data) {}

    // ── Read inbox ─────────────────────────────────────────────────────────────

    public List<EmailSummary> getInbox(int count) throws Exception {
        ListMessagesResponse response = gmail.users().messages()
                .list("me")
                .setMaxResults((long) Math.min(count, 10))
                .setQ("in:inbox -category:promotions -category:social")
                .execute();

        List<EmailSummary> emails = new ArrayList<>();
        if (response.getMessages() == null) return emails;

        for (Message msg : response.getMessages()) {
            Message full = gmail.users().messages()
                    .get("me", msg.getId())
                    .setFormat("metadata")
                    .setMetadataHeaders(List.of("From", "Subject", "Date"))
                    .execute();
            String from    = getHeader(full, "From");
            String subject = getHeader(full, "Subject");
            String date    = getHeader(full, "Date");
            String snippet = full.getSnippet();
            if (snippet != null && snippet.length() > 120) snippet = snippet.substring(0, 120) + "...";
            emails.add(new EmailSummary(full.getId(), from, subject, date, snippet));
        }
        return emails;
    }

    // ── Read full email content ───────────────────────────────────────────────

    /** Fetch and decode the full body of a specific email by message ID. */
    public EmailContent readEmailContent(String messageId) throws Exception {
        Message full = gmail.users().messages()
                .get("me", messageId)
                .setFormat("full")
                .execute();
        String from    = getHeader(full, "From");
        String subject = getHeader(full, "Subject");
        String date    = getHeader(full, "Date");
        String body    = extractBody(full.getPayload());
        return new EmailContent(full.getId(), from, subject, date, body, full.getThreadId());
    }

    /**
     * Recursively extract plain-text body from a message payload.
     * Prefers text/plain; falls back to text/html (tags stripped); recurses into multipart.
     */
    private String extractBody(MessagePart payload) {
        if (payload == null) return "";
        // Leaf part with data
        if (payload.getBody() != null && payload.getBody().getData() != null) {
            byte[] bytes = Base64.getUrlDecoder().decode(payload.getBody().getData());
            String text  = new String(bytes, StandardCharsets.UTF_8);
            if ("text/html".equalsIgnoreCase(payload.getMimeType())) {
                text = text.replaceAll("(?i)<br\\s*/?>", "\n")
                           .replaceAll("(?i)<p[^>]*>", "\n")
                           .replaceAll("<[^>]+>", "")
                           // Named HTML entities
                           .replaceAll("&nbsp;", " ")
                           .replaceAll("&amp;", "&")
                           .replaceAll("&lt;", "<")
                           .replaceAll("&gt;", ">")
                           .replaceAll("&quot;", "\"")
                           .replaceAll("&apos;", "'")
                           .replaceAll("&mdash;", "—")
                           .replaceAll("&ndash;", "–")
                           .replaceAll("&rsquo;", "'")
                           .replaceAll("&lsquo;", "'")
                           .replaceAll("&rdquo;", "\u201D")
                           .replaceAll("&ldquo;", "\u201C")
                           .replaceAll("&bull;", "•")
                           .replaceAll("&hellip;", "…")
                           .replaceAll("&copy;", "©")
                           .replaceAll("&reg;", "®")
                           .replaceAll("&trade;", "™")
                           .replaceAll("\r\n|\r", "\n")
                           .replaceAll("\n{3,}", "\n\n")
                           .trim();
                // Decode remaining numeric HTML entities: &#NNN; and &#xHHH;
                text = decodeNumericEntities(text);
            }
            return text;
        }
        // Multipart: prefer text/plain, then text/html, then recurse
        if (payload.getParts() != null) {
            String plain = null, html = null;
            for (MessagePart part : payload.getParts()) {
                if ("text/plain".equalsIgnoreCase(part.getMimeType()))
                    plain = extractBody(part);
                else if ("text/html".equalsIgnoreCase(part.getMimeType()) && html == null)
                    html = extractBody(part);
            }
            if (plain != null && !plain.isBlank()) return plain;
            if (html  != null && !html.isBlank())  return html;
            // Recurse into nested multipart (e.g. multipart/alternative inside multipart/mixed)
            for (MessagePart part : payload.getParts()) {
                String body = extractBody(part);
                if (!body.isBlank()) return body;
            }
        }
        return "";
    }

    // ── Create draft ──────────────────────────────────────────────────────────

    /** Create a new draft, optionally with file attachments. */
    public String createDraft(String to, String cc, String subject, String body,
                              List<Attachment> attachments) throws Exception {
        String raw = (attachments == null || attachments.isEmpty())
                ? buildPlainMime(to, cc, null, subject, body, null)
                : buildMultipartMime(to, cc, null, subject, body, null, attachments);

        Message message = new Message();
        message.setRaw(Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes("UTF-8")));
        Draft draft = new Draft().setMessage(message);
        Draft created = gmail.users().drafts().create("me", draft).execute();
        return created.getId();
    }

    // ── Reply as draft ────────────────────────────────────────────────────────

    /** Create a reply draft to an existing email, preserving the thread. */
    public String replyToDraft(String originalMessageId, String body,
                               List<Attachment> attachments) throws Exception {
        // Fetch original message headers to build correct reply headers
        Message original = gmail.users().messages()
                .get("me", originalMessageId)
                .setFormat("metadata")
                .setMetadataHeaders(List.of("From", "Subject", "Message-ID", "To", "Cc"))
                .execute();

        String from      = getHeader(original, "From");
        String origSubj  = getHeader(original, "Subject");
        String origCc    = getHeader(original, "Cc");
        String messageId = getHeader(original, "Message-ID");
        String threadId  = original.getThreadId();

        // Reply goes TO the original sender, CC preserved; subject gets "Re:" prefix if not already there
        String replySubj = origSubj.startsWith("Re:") ? origSubj : "Re: " + origSubj;
        String cc = (origCc != null && !origCc.isBlank()) ? origCc : null;

        String raw = (attachments == null || attachments.isEmpty())
                ? buildPlainMime(from, cc, null, replySubj, body, messageId)
                : buildMultipartMime(from, cc, null, replySubj, body, messageId, attachments);

        Message msg = new Message();
        msg.setRaw(Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes("UTF-8")));
        msg.setThreadId(threadId);

        Draft draft = new Draft().setMessage(msg);
        Draft created = gmail.users().drafts().create("me", draft).execute();
        return created.getId();
    }

    // ── MIME builders ─────────────────────────────────────────────────────────

    /** Plain text MIME message. inReplyTo may be null. */
    private String buildPlainMime(String to, String cc, String bcc, String subject,
                                   String body, String inReplyTo) {
        body = body + SIGNATURE;
        StringBuilder sb = new StringBuilder();
        sb.append("To: ").append(encodeAddressHeader(to)).append("\r\n");
        if (cc  != null && !cc.isBlank())  sb.append("CC: ").append(encodeAddressHeader(cc)).append("\r\n");
        if (bcc != null && !bcc.isBlank()) sb.append("BCC: ").append(encodeAddressHeader(bcc)).append("\r\n");
        sb.append("Subject: ").append(encodeHeader(subject)).append("\r\n");
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            sb.append("In-Reply-To: ").append(inReplyTo).append("\r\n");
            sb.append("References: ").append(inReplyTo).append("\r\n");
        }
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: text/plain; charset=utf-8\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n");
        sb.append("\r\n");
        sb.append(Base64.getMimeEncoder(76, "\r\n".getBytes()).encodeToString(body.getBytes(StandardCharsets.UTF_8)));
        return sb.toString();
    }

    /** Multipart/mixed MIME message with file attachments. */
    private String buildMultipartMime(String to, String cc, String bcc, String subject,
                                       String body, String inReplyTo, List<Attachment> attachments) {
        body = body + SIGNATURE;
        String boundary = "==taskbot_" + System.currentTimeMillis() + "==";
        StringBuilder sb = new StringBuilder();
        sb.append("To: ").append(encodeAddressHeader(to)).append("\r\n");
        if (cc  != null && !cc.isBlank())  sb.append("CC: ").append(encodeAddressHeader(cc)).append("\r\n");
        if (bcc != null && !bcc.isBlank()) sb.append("BCC: ").append(encodeAddressHeader(bcc)).append("\r\n");
        sb.append("Subject: ").append(encodeHeader(subject)).append("\r\n");
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            sb.append("In-Reply-To: ").append(inReplyTo).append("\r\n");
            sb.append("References: ").append(inReplyTo).append("\r\n");
        }
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n");
        sb.append("\r\n");

        // Body part (base64-encoded to safely handle non-ASCII)
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: text/plain; charset=utf-8\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
        sb.append(Base64.getMimeEncoder(76, "\r\n".getBytes()).encodeToString(body.getBytes(StandardCharsets.UTF_8))).append("\r\n");

        // Attachment parts
        for (Attachment att : attachments) {
            String safeName = att.filename().replaceAll("[\\r\\n\"\\\\]", "_");
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Type: application/octet-stream; name=\"").append(safeName).append("\"\r\n");
            sb.append("Content-Transfer-Encoding: base64\r\n");
            sb.append("Content-Disposition: attachment; filename=\"").append(safeName).append("\"\r\n\r\n");
            sb.append(Base64.getMimeEncoder(76, "\r\n".getBytes()).encodeToString(att.data())).append("\r\n");
        }
        sb.append("--").append(boundary).append("--\r\n");
        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Decode numeric HTML entities like &#8217; (decimal) and &#x2019; (hex)
     * into their Unicode character equivalents.
     */
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    private static String decodeNumericEntities(String text) {
        Matcher m = NUMERIC_ENTITY.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            try {
                int codePoint = m.group(1).isEmpty()
                        ? Integer.parseInt(m.group(2))
                        : Integer.parseInt(m.group(2), 16);
                m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(codePoint))));
            } catch (Exception e) {
                // Malformed entity — leave as-is
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * RFC 2047 encodes a header value (e.g. Subject) so that any non-ASCII
     * characters — smart quotes, apostrophes, accents, etc. — are transmitted
     * as Base64-encoded UTF-8 instead of raw bytes.  Without this, email clients
     * that default to Latin-1 show "â€™" instead of a plain apostrophe.
     */
    private static String encodeHeader(String value) {
        // Only encode if there are non-ASCII characters
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) {
                return "=?UTF-8?B?"
                        + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))
                        + "?=";
            }
        }
        return value;
    }

    /**
     * RFC 2047 encodes the display-name portion of an email address header.
     * E.g. "Stéphane <s@example.com>" → "=?UTF-8?B?U3TDqXBoYW5l?= <s@example.com>"
     * Plain ASCII addresses like "foo@bar.com" pass through unchanged.
     */
    private static String encodeAddressHeader(String address) {
        if (address == null || address.isBlank()) return address;
        // If there's an angle-bracket address, only encode the display name
        int angleStart = address.lastIndexOf('<');
        if (angleStart > 0) {
            String displayName = address.substring(0, angleStart).trim();
            String addrPart    = address.substring(angleStart); // "<email@example.com>"
            return encodeHeader(displayName) + " " + addrPart;
        }
        // No display name — bare email, nothing to encode
        return address;
    }

    private String getHeader(Message msg, String name) {
        if (msg.getPayload() == null || msg.getPayload().getHeaders() == null) return "";
        return msg.getPayload().getHeaders().stream()
                .filter(h -> name.equalsIgnoreCase(h.getName()))
                .map(MessagePartHeader::getValue)
                .findFirst().orElse("");
    }
}
