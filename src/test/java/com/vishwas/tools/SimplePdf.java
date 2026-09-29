package com.vishwas.tools;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal single-page text PDF writer (Helvetica, A4) for the sample vendor letters. Enough for any PDF
 * parser to extract the text; no external library needed.
 */
public final class SimplePdf {

    private SimplePdf() {
    }

    /** Lines starting with "# " are set in bold at a larger size; blank lines add spacing. */
    public static byte[] render(List<String> lines) {
        StringBuilder content = new StringBuilder("BT\n");
        int y = 790;
        for (String raw : lines) {
            boolean heading = raw.startsWith("# ");
            String text = heading ? raw.substring(2) : raw;
            for (String line : wrap(text, heading ? 60 : 92)) {
                content.append(heading ? "/F2 14 Tf\n" : "/F1 10.5 Tf\n")
                        .append("1 0 0 1 56 ").append(y).append(" Tm\n")
                        .append('(').append(escape(line)).append(") Tj\n");
                y -= heading ? 20 : 15;
            }
            if (text.isBlank()) {
                y -= 4;
            }
        }
        content.append("ET\n");

        List<String> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        objects.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R "
                + "/Resources << /Font << /F1 5 0 R /F2 6 0 R >> >> >>");
        byte[] stream = content.toString().getBytes(StandardCharsets.ISO_8859_1);
        objects.add("<< /Length " + stream.length + " >>\nstream\n" + content + "endstream");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "%PDF-1.4\n%âãÏÓ\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }
        int xref = out.size();
        StringBuilder x = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int off : offsets) {
            x.append(String.format("%010d 00000 n \n", off));
        }
        x.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        write(out, x.toString());
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        out.write(b, 0, b.length);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        if (text.isBlank()) {
            out.add("");
            return out;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > width && line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        out.add(line.toString());
        return out;
    }
}
