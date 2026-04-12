package com.haizul.taskbot;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Draft;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePartHeader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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
        String messageId = getHeader(original, "Message-ID");
        String threadId  = original.getThreadId();

        // Reply goes TO the original sender; subject gets "Re:" prefix if not already there
        String replySubj = origSubj.startsWith("Re:") ? origSubj : "Re: " + origSubj;

        String raw = (attachments == null || attachments.isEmpty())
                ? buildPlainMime(from, null, null, replySubj, body, messageId)
                : buildMultipartMime(from, null, null, replySubj, body, messageId, attachments);

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
        sb.append("To: ").append(to).append("\r\n");
        if (cc  != null && !cc.isBlank())  sb.append("CC: ").append(cc).append("\r\n");
        if (bcc != null && !bcc.isBlank()) sb.append("BCC: ").append(bcc).append("\r\n");
        sb.append("Subject: ").append(encodeHeader(subject)).append("\r\n");
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            sb.append("In-Reply-To: ").append(inReplyTo).append("\r\n");
            sb.append("References: ").append(inReplyTo).append("\r\n");
        }
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: text/plain; charset=utf-8\r\n");
        sb.append("\r\n");
        sb.append(body);
        return sb.toString();
    }

    /** Multipart/mixed MIME message with file attachments. */
    private String buildMultipartMime(String to, String cc, String bcc, String subject,
                                       String body, String inReplyTo, List<Attachment> attachments) {
        body = body + SIGNATURE;
        String boundary = "==taskbot_" + System.currentTimeMillis() + "==";
        StringBuilder sb = new StringBuilder();
        sb.append("To: ").append(to).append("\r\n");
        if (cc  != null && !cc.isBlank())  sb.append("CC: ").append(cc).append("\r\n");
        if (bcc != null && !bcc.isBlank()) sb.append("BCC: ").append(bcc).append("\r\n");
        sb.append("Subject: ").append(encodeHeader(subject)).append("\r\n");
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            sb.append("In-Reply-To: ").append(inReplyTo).append("\r\n");
            sb.append("References: ").append(inReplyTo).append("\r\n");
        }
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n");
        sb.append("\r\n");

        // Body part
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: text/plain; charset=utf-8\r\n\r\n");
        sb.append(body).append("\r\n");

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

    private String getHeader(Message msg, String name) {
        if (msg.getPayload() == null || msg.getPayload().getHeaders() == null) return "";
        return msg.getPayload().getHeaders().stream()
                .filter(h -> name.equalsIgnoreCase(h.getName()))
                .map(MessagePartHeader::getValue)
                .findFirst().orElse("");
    }
}
