import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/** Validate article/media PDF pages against generated JS expectations. */
public class ValidatePages {
    static class LineBox {
        int page;
        String text;
        LineBox(int page, String text) { this.page = page; this.text = clean(text); }
    }

    static String clean(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFKC).replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    static boolean matchesMediaCaption(String text, String ref, String type) {
        String t = clean(text).replaceAll("\\s+", "");
        String prefix = "figure".equals(type) ? "\u5716" : "\u8868";
        String exact = prefix + ref;
        if (t.equals(exact)) return true;
        if (t.startsWith(exact + ")") || t.startsWith(exact + "\uFF09")) return true;
        if (t.startsWith(exact + ".") || t.startsWith(exact + "\uFF0E")) return true;
        return false;
    }

    static int captionScore(String text, String ref, String type) {
        if (!matchesMediaCaption(text, ref, type)) return 0;
        String t = clean(text).replaceAll("\\s+", "");
        String prefix = "figure".equals(type) ? "\u5716" : "\u8868";
        String exact = prefix + ref;
        if (t.equals(exact)) return 100;
        if (t.matches("^" + Pattern.quote(exact) + "[\\)\\uFF09]?\\.?$")) return 90;
        return 50;
    }

    static int findMediaPdfPage(List<LineBox> allLines, String ref, String type) {
        int best = -1, bestScore = 0;
        for (LineBox lb : allLines) {
            int score = captionScore(lb.text, ref, type);
            if (score > bestScore || (score == bestScore && score > 0 && lb.page > best)) {
                bestScore = score;
                best = lb.page;
            }
        }
        return best;
    }

    static int findArticlePdfPage(List<LineBox> allLines, String artId) {
        if (artId.startsWith("\u5716-") || artId.startsWith("\u8868-") || artId.startsWith("APP-")) return -1;
        Pattern pat = Pattern.compile("^" + Pattern.quote(artId) + "(?:\\s|[\uFF1A:]|$)");
        for (LineBox lb : allLines) {
            if (pat.matcher(clean(lb.text)).find()) return lb.page;
        }
        return -1;
    }

    static List<LineBox> extractLines(PDDocument doc) throws IOException {
        List<LineBox> all = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            int activePage = 0;
            @Override protected void startPage(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
                super.startPage(page);
                activePage = getCurrentPageNo();
            }
            @Override protected void writeString(String string, List<TextPosition> textPositions) {
                if (!string.trim().isEmpty()) all.add(new LineBox(activePage, string));
            }
        };
        stripper.getText(doc);
        return all;
    }

    static class Entry {
        String art, type, ref;
        int jsPage, pdfPage;
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath();
        Path pdfFile = root.resolve("assets/accessibility-spec.pdf");
        Path jsFile = root.resolve("js/accessibility-spec.js");
        String js = Files.readString(jsFile, StandardCharsets.UTF_8);

        Pattern artPat = Pattern.compile(
                "\\{ art: '([^']+)', label: '[^']*', body: '[^']*', pages: \\[([^\\]]*)\\], media: \\[(.*?)\\] \\}");
        Matcher m = artPat.matcher(js);

        List<Entry> mismatches = new ArrayList<>();
        int totalArts = 0, totalMedia = 0;

        try (PDDocument doc = Loader.loadPDF(pdfFile.toFile())) {
            List<LineBox> allLines = extractLines(doc);

            while (m.find()) {
                totalArts++;
                String artId = m.group(1);
                int jsArtPage = -1;
                String pagesStr = m.group(2).trim();
                if (!pagesStr.isEmpty()) {
                    String[] parts = pagesStr.split(",\\s*");
                    jsArtPage = Integer.parseInt(parts[parts.length - 1]);
                }

                int pdfArtPage = findArticlePdfPage(allLines, artId);
                if (pdfArtPage > 0 && jsArtPage > 0 && !pagesStr.contains(String.valueOf(pdfArtPage))) {
                    Entry e = new Entry();
                    e.art = artId; e.type = "article"; e.ref = artId;
                    e.jsPage = jsArtPage; e.pdfPage = pdfArtPage;
                    mismatches.add(e);
                } else if (pdfArtPage > 0 && jsArtPage > 0 && pdfArtPage != jsArtPage && !pagesStr.contains(",")) {
                    Entry e = new Entry();
                    e.art = artId; e.type = "article-primary"; e.ref = artId;
                    e.jsPage = jsArtPage; e.pdfPage = pdfArtPage;
                    mismatches.add(e);
                }

                String mediaBlock = m.group(3);
                Pattern medPat = Pattern.compile("\\{ type: '([^']+)', ref: '([^']+)', caption: '[^']*', page: (\\d+) \\}");
                Matcher mm = medPat.matcher(mediaBlock);
                while (mm.find()) {
                    totalMedia++;
                    String type = mm.group(1);
                    String ref = mm.group(2);
                    int jsPage = Integer.parseInt(mm.group(3));
                    int pdfPage = findMediaPdfPage(allLines, ref, type);
                    if (pdfPage > 0 && pdfPage != jsPage) {
                        Entry e = new Entry();
                        e.art = artId; e.type = type; e.ref = ref;
                        e.jsPage = jsPage; e.pdfPage = pdfPage;
                        mismatches.add(e);
                    } else if (pdfPage <= 0) {
                        Entry e = new Entry();
                        e.art = artId; e.type = type + "-missing"; e.ref = ref;
                        e.jsPage = jsPage; e.pdfPage = -1;
                        mismatches.add(e);
                    }
                }
            }
        }

        System.out.println("Articles: " + totalArts + ", media refs: " + totalMedia);
        System.out.println("Mismatches: " + mismatches.size());
        for (Entry e : mismatches) {
            System.out.println(e.art + " | " + e.type + " " + e.ref + " | JS=" + e.jsPage + " PDF=" + e.pdfPage);
        }
    }
}
