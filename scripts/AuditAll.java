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

/** Full audit: every article/media page vs PDF line scan. */
public class AuditAll {
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
        String t = clean(text);
        String prefix = "figure".equals(type) ? "\u5716" : "\u8868";
        if (t.contains("\u5982\u5716") && !t.startsWith(prefix)) return 0;
        if (!matchesMediaCaption(text, ref, type)) return 0;
        String compact = t.replaceAll("\\s+", "");
        String exact = prefix + ref;
        if (compact.equals(exact)) return 100;
        if (compact.matches("^" + Pattern.quote(exact) + "[\\)\\uFF09]?\\.?$")) return 90;
        return 50;
    }

    static boolean isTocLine(String text) {
        String t = clean(text);
        if (t.contains("\u22EF") || t.contains("\u2026")) return true;
        if (t.matches(".*\\?{3,}.*")) return true;
        long dots = t.chars().filter(c -> c == '.' || c == '\uFF0E').count();
        if (dots >= 5) return true;
        if (t.matches("^A\\d{3}(?:\\.\\d+)*\\s+.*\\d{1,3}\\s*$")
                && !t.contains("\uFF1A") && !t.contains(":") && t.length() > 24) return true;
        return false;
    }

    static int articleLineScore(String text, String artId) {
        String t = clean(text);
        if (isTocLine(t)) return 0;
        if (!t.startsWith(artId)) return 0;
        String rest = t.substring(artId.length()).trim();
        if (rest.isEmpty()) return 40;
        char c0 = rest.charAt(0);
        if (c0 == '(' || c0 == '\uFF08' || rest.startsWith("\u5982\u5716")
                || rest.startsWith("\u3001") || rest.startsWith("\u53CA")) return 0;
        if (c0 == '\uFF1A' || c0 == ':' || c0 >= '\u4e00') return 100;
        return 50;
    }

    static boolean isAppendixArticleId(String artId) {
        return artId.startsWith("A") && artId.length() >= 4 && Character.isDigit(artId.charAt(1));
    }

    static int findCaptionPdfPage(List<LineBox> allLines, String ref, String type) {
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

    static boolean isRelatedArticleId(String other, String artId) {
        if (other.equals(artId)) return true;
        return artId.startsWith(other + ".");
    }

    static boolean hasOtherArticleStartsOnPage(List<LineBox> allLines, int page, String artId) {
        Pattern subArt = Pattern.compile("^(\\d{3,4}\\.\\d+(?:\\.\\d+)?)\\s+");
        Pattern mainArt = Pattern.compile("^(\\d{3,4})\\s+");
        Pattern appArt = Pattern.compile("^(A\\d{3}(?:\\.\\d+(?:\\.\\d+)?)?)\\s+");
        for (LineBox lb : allLines) {
            if (lb.page != page) continue;
            String t = clean(lb.text);
            Matcher appM = appArt.matcher(t);
            if (appM.find()) {
                String id = appM.group(1);
                if (!isRelatedArticleId(id, artId) && articleLineScore(t, id) >= 100) return true;
                continue;
            }
            Matcher subM = subArt.matcher(t);
            if (subM.find()) {
                String id = subM.group(1);
                if (!isRelatedArticleId(id, artId) && articleLineScore(t, id) >= 100) return true;
                continue;
            }
            Matcher mainM = mainArt.matcher(t);
            if (mainM.find() && !t.matches("\\d{3,4}\\.\\d+.*")) {
                String id = mainM.group(1);
                if (!isRelatedArticleId(id, artId) && articleLineScore(t, id) >= 100) return true;
            }
        }
        return false;
    }

    static int resolveMediaPdfPage(List<LineBox> allLines, String ref, String type, String artId, int artPage) {
        int captionPage = findCaptionPdfPage(allLines, ref, type);
        if (captionPage <= 0) return artPage > 0 ? artPage : -1;

        int ownerArtPage = findArticlePdfPage(allLines, ref);
        if (ownerArtPage > 0) {
            artPage = ownerArtPage;
            artId = ref;
        }

        if (captionPage == artPage) return captionPage;
        if (artPage > 0 && captionPage == artPage + 1) {
            if (hasOtherArticleStartsOnPage(allLines, artPage, artId)) return captionPage;
            return artPage;
        }
        return captionPage;
    }

    static int findMediaPdfPage(List<LineBox> allLines, String ref, String type, String artId) {
        int artPage = findArticlePdfPage(allLines, artId);
        return resolveMediaPdfPage(allLines, ref, type, artId, artPage);
    }

    static int findArticlePdfPage(List<LineBox> allLines, String artId) {
        if (artId.startsWith("\u5716-") || artId.startsWith("\u8868-") || artId.startsWith("APP-")) return -1;
        int best = -1, bestScore = 0;
        boolean appendixArt = isAppendixArticleId(artId);
        for (LineBox lb : allLines) {
            int score = articleLineScore(lb.text, artId);
            if (score <= 0) continue;
            if (appendixArt) {
                if (score >= bestScore && lb.page > best) {
                    bestScore = score;
                    best = lb.page;
                }
            } else if (score > bestScore) {
                bestScore = score;
                best = lb.page;
            }
        }
        return best;
    }

    static int uiPage(String type, int jsMediaPage, List<Integer> pages) {
        if (jsMediaPage > 0 && "figure".equals(type)) return jsMediaPage;
        if (jsMediaPage > 0) return jsMediaPage;
        if (!pages.isEmpty()) return pages.get(pages.size() - 1);
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

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath();
        Path pdfFile = root.resolve("assets/accessibility-spec.pdf");
        Path jsFile = root.resolve("js/accessibility-spec.js");
        String js = Files.readString(jsFile, StandardCharsets.UTF_8);

        Pattern artPat = Pattern.compile(
                "\\{ art: '([^']+)', label: '[^']*', body: '[^']*', pages: \\[([^\\]]*)\\], media: \\[(.*?)\\] \\}");
        Matcher m = artPat.matcher(js);

        int issues = 0, arts = 0, media = 0;
        List<LineBox> allLines;
        try (PDDocument doc = Loader.loadPDF(pdfFile.toFile())) {
            allLines = extractLines(doc);
        }

        while (m.find()) {
            arts++;
            String artId = m.group(1);
            List<Integer> jsPages = new ArrayList<>();
            for (String p : m.group(2).split(",")) {
                p = p.trim();
                if (!p.isEmpty()) jsPages.add(Integer.parseInt(p));
            }

            int pdfArt = findArticlePdfPage(allLines, artId);
            if (pdfArt > 0 && !jsPages.isEmpty() && !jsPages.contains(pdfArt)) {
                System.out.println("ART-PAGE-MISS " + artId + " js=" + jsPages + " pdfArt=" + pdfArt);
                issues++;
            } else if (pdfArt <= 0 && !artId.startsWith("\u5716-") && !artId.startsWith("\u8868-") && !artId.startsWith("APP-")) {
                System.out.println("ART-NOT-FOUND " + artId);
                issues++;
            }

            String mediaBlock = m.group(3);
            Pattern medPat = Pattern.compile("\\{ type: '([^']+)', ref: '([^']+)', caption: '[^']*', page: (\\d+) \\}");
            Matcher mm = medPat.matcher(mediaBlock);
            while (mm.find()) {
                media++;
                String type = mm.group(1);
                String ref = mm.group(2);
                int jsPage = Integer.parseInt(mm.group(3));
                int pdfPage = findMediaPdfPage(allLines, ref, type, artId);
                if (pdfPage <= 0) {
                    System.out.println("MEDIA-NOT-FOUND " + artId + " " + type + " " + ref + " js=" + jsPage);
                    issues++;
                } else if (pdfPage != jsPage) {
                    System.out.println("MEDIA-MISMATCH " + artId + " " + ref + " js=" + jsPage + " pdf=" + pdfPage);
                    issues++;
                }
                int expectedUi = uiPage(type, pdfPage > 0 ? pdfPage : jsPage, jsPages);
                if (expectedUi > 0 && expectedUi != jsPage && "figure".equals(type)) {
                    System.out.println("UI-WOULD-BE " + artId + " expected=" + expectedUi + " mediaJs=" + jsPage);
                }
            }
        }
        System.out.println("---");
        System.out.println("Articles: " + arts + ", media: " + media + ", issues: " + issues);
        if (issues == 0) System.out.println("OK: all pages match PDF scan.");
    }
}
