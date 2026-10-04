package com.prism.gateway.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

/** What the gateway sends back: a complete JSON body, or an SSE stream written as it is produced. */
public sealed interface GatewayReply {

    void writeTo(HttpServletResponse response) throws IOException;

    record Json(int status, Map<String, String> headers, String body) implements GatewayReply {

        @Override
        public void writeTo(HttpServletResponse response) throws IOException {
            response.setStatus(status);
            headers.forEach(response::setHeader);
            response.setContentType("application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
        }
    }

    record Stream(Map<String, String> headers, Pump pump) implements GatewayReply {

        @Override
        public void writeTo(HttpServletResponse response) throws IOException {
            response.setStatus(200);
            headers.forEach(response::setHeader);
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("X-Accel-Buffering", "no");
            // Commit status + headers now, before the first token, so clients see them immediately.
            try {
                response.flushBuffer();
            } catch (IOException clientGone) {
                // Still run the pump: its first write fails, which closes the upstream and logs the request.
            }
            pump.run(new SseWriter(response.getOutputStream(), response));
        }
    }

    @FunctionalInterface
    interface Pump {
        void run(SseWriter writer);
    }

    /** Writes one SSE event and flushes it to the socket immediately. */
    final class SseWriter {

        private final OutputStream out;
        private final HttpServletResponse response;

        SseWriter(OutputStream out, HttpServletResponse response) {
            this.out = out;
            this.response = response;
        }

        /** @throws IOException when the client has gone away */
        public void data(String payload) throws IOException {
            out.write(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            response.flushBuffer();
        }
    }
}
