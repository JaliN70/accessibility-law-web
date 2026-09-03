import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/** Export accessibility spec: chapters, articles, media refs, page PNGs, JS bundle. */
public class ExportAccessibilityPages {
    static class LineBox {
        int page;
        float minX, minY, maxX, maxY;
        String text;

        LineBox(int page, float minX, float minY, float maxX, float maxY, String text) {
            this.page = page;
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.text = clean(text);
        }
    }

    static class MediaRef {
        String type, ref, caption;
        int page;
    }

    static class Article {
        String id, title, label, body;
        List<Integer> pages = new ArrayList<>();
        List<MediaRef> media = new ArrayList<>();
    }

    static class Chapter {
        String id, icon, title, subtitle, desc;
        List<Article> articles = new ArrayList<>();
    }

    static class PageLinesStripper extends PDFTextStripper {
        final List<LineBox> lines = new ArrayList<>();
        int activePage = 0;

        PageLinesStripper() throws IOException {
            super();
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            super.startPage(page);
            activePage = getCurrentPageNo();
        }

        @Override
        protected void writeString(String string, List<TextPosition> textPositions) {
            if (textPositions.isEmpty()) return;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = 0, maxY = 0;
            for (TextPosition tp : textPositions) {
                minX = Math.min(minX, tp.getX());
                minY = Math.min(minY, tp.getY());
                maxX = Math.max(maxX, tp.getX() + tp.getWidth());
                maxY = Math.max(maxY, tp.getY() + tp.getHeight());
            }
            lines.add(new LineBox(activePage, minX, minY, maxX, maxY, string));
        }
    }

    static String normalizeText(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFKC);
    }

    static String clean(String s) {
        if (s == null) return "";
        return normalizeText(s).replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    static String shortLabel(String text) {
        text = clean(text);
        if (text.length() <= 36) return text;
        return text.substring(0, 36) + "\u2026";
    }

    static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    static int chineseChapterNum(String s) {
        Map<String, Integer> m = Map.ofEntries(
                Map.entry("\u4e00", 1), Map.entry("\u4e8c", 2), Map.entry("\u4e09", 3),
                Map.entry("\u56db", 4), Map.entry("\u4e94", 5), Map.entry("\u516d", 6),
                Map.entry("\u4e03", 7), Map.entry("\u516b", 8), Map.entry("\u4e5d", 9),
                Map.entry("\u5341", 10));
        if ("\u5341".equals(s)) return 10;
        if (s.startsWith("\u5341") && s.length() == 2) return 10 + m.getOrDefault(s.substring(1), 0);
        return m.getOrDefault(s, 0);
    }

    static String chapterIcon(String title) {
        if (title.contains("\u7e3d\u5247")) return "\uD83D\uDCCB";
        if (title.contains("\u901a\u8def")) return "\uD83D\uDEB6";
        if (title.contains("\u6a13\u68af")) return "\uD83D\uDEB6\u200D\u2642\uFE0F";
        if (title.contains("\u6607\u964d")) return "\uD83D\uDE87";
        if (title.contains("\u5ec1\u6240")) return "\uD83D\uDEBF";
        if (title.contains("\u6d74\u5ba4")) return "\uD83D\uDEBF";
        if (title.contains("\u89c0\u773e")) return "\uD83C\uDFAD";
        if (title.contains("\u505c\u8eca")) return "\uD83C\uDD7F\uFE0F";
        if (title.contains("\u6a19\u8a8c")) return "\u267F";
        if (title.contains("\u5ba2\u623f")) return "\uD83C\uDFE8";
        return "\uD83D\uDCCA";
    }

    static boolean isPageMarker(String line) {
        String t = line.trim();
        return t.matches("\\d{1,3}");
    }

    static boolean hasPageHeader(List<LineBox> pageLines, float pageH) {
        for (LineBox lb : pageLines) {
            if (lb.minY < pageH * 0.85f) continue;
            String t = lb.text.trim();
            if (t.matches("\\d{1,3}")) return true;
        }
        return false;
    }

    static Integer findPrintedPageNum(List<LineBox> pageLines, float pageH) {
        Integer printed = null;
        for (LineBox lb : pageLines) {
            if (lb.minY < pageH * 0.85f) continue;
            String t = lb.text.trim();
            if (t.matches("\\d{1,3}")) printed = Integer.parseInt(t);
        }
        return printed;
    }

    static Map<Integer, Integer> buildPrintedToPdfMap(PDDocument doc, List<LineBox> allLines) {
        Map<Integer, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            int pdfPage = i + 1;
            float pageH = doc.getPage(i).getMediaBox().getHeight();
            List<LineBox> pageLines = new ArrayList<>();
            for (LineBox lb : allLines) if (lb.page == pdfPage) pageLines.add(lb);
            Integer printed = findPrintedPageNum(pageLines, pageH);
            if (printed != null) map.putIfAbsent(printed, pdfPage);
        }
        return map;
    }

    static List<int[]> buildMarkerEvents(List<String> lines, int scanStart, Map<Integer, Integer> printedToPdf) {
        List<int[]> markerEvents = new ArrayList<>();
        for (int i = scanStart; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (!line.isEmpty() && isPageMarker(line)) {
                int printed = Integer.parseInt(line);
                Integer pdf = printedToPdf.get(printed);
                if (pdf != null) markerEvents.add(new int[] { i, pdf });
            }
        }
        return markerEvents;
    }

    static int pageAtLine(int lineIdx, List<int[]> markerEvents, int defaultPage) {
        int page = defaultPage;
        for (int[] ev : markerEvents) {
            if (ev[0] <= lineIdx) page = ev[1];
            else break;
        }
        return page;
    }

    static boolean isFigureLine(String line) {
        String compact = clean(line).replaceAll("\\s+", "");
        return compact.matches("\u5716[A]?\\d+(?:\\.\\d+)+[\\)\\uFF09]?\\.?")
                || compact.matches("\u8868[A]?\\d+(?:\\.\\d+)+[\\)\\uFF09]?\\.?");
    }

    static String figureCaption(String ref) {
        return "\u5716" + ref;
    }

    static String mediaType(String line) {
        return clean(line).startsWith("\u5716") ? "figure" : "table";
    }

    static String mediaRef(String line) {
        String t = clean(line);
        Matcher m = Pattern.compile("[\u5716\u8868]\\s*([A]?\\d+(?:\\.\\d+)*)").matcher(t);
        if (m.find()) return m.group(1).replaceAll("\\s+", "");
        return t.substring(0, Math.min(16, t.length())).replaceAll("\\s+", "");
    }

    static String mediaArtId(String line) {
        return (clean(line).startsWith("\u5716") ? "\u5716-" : "\u8868-") + mediaRef(line);
    }

    static boolean isNumericGarbage(String text) {
        text = clean(text);
        if (text.isEmpty()) return true;
        if (text.matches("^[\\d\\.\\s\\-,\\*\\uFF0C\\u3001/]+$")) return true;
        if (text.matches("^[\\d\\.]+\\s+[\\d\\.\\s\\-]+$")) return true;
        return text.length() < 8 && text.matches(".*\\d.*") && !text.matches(".*[\u4e00-\u9fff].*");
    }

    static String[] parseAppendixSection(String line) {
        if (isPageMarker(line)) return null;
        Matcher m = Pattern.compile("^\u9644\u9304\\s*(\\d+)\\s+(.+)$").matcher(clean(line));
        if (!m.matches()) return null;
        String title = clean(m.group(2));
        if (title.length() <= 2 || !title.matches(".*[\u4e00-\u9fff].*")) return null;
        return new String[] { "APP-" + m.group(1), title };
    }

    static boolean isAppendixArticle(String line) {
        return line.matches("^A\\d{3}(?:\\.\\d+(?:\\.\\d+)?)?\\s+.*");
    }

    static void flushArticle(Chapter chapter, Article art, List<String> bodyLines, int startPage, int endPage) {
        if (chapter == null || art == null) return;
        boolean tableOrFigure = art.id.startsWith("\u8868-") || art.id.startsWith("\u5716-");
        List<String> paras = new ArrayList<>();
        if (tableOrFigure) {
            paras.add(art.title);
        } else {
            for (String line : bodyLines) {
                String t = clean(line);
                if (!t.isEmpty() && !isNumericGarbage(t)) paras.add(t);
            }
        }
        String body = String.join("\n\n", paras);
        if (body.isEmpty()) body = art.title;
        art.body = body;
        if (tableOrFigure) {
            art.label = art.title.length() <= 48 ? art.title : shortLabel(art.title);
        } else if (art.id.matches("APP-\\d+")) {
            art.label = art.title;
        } else {
            String flatBody = clean(body.replace("\n\n", " "));
            String labelSource;
            if (art.title.isEmpty()) labelSource = flatBody;
            else if (flatBody.startsWith(art.title) || flatBody.equals(art.title)) labelSource = art.title;
            else labelSource = art.title + " " + flatBody;
            art.label = shortLabel(labelSource);
        }
        LinkedHashSet<Integer> pageSet = new LinkedHashSet<>();
        int lo = Math.min(startPage, Math.max(endPage, startPage));
        int hi = Math.max(startPage, endPage);
        for (int p = lo; p <= hi; p++) if (p > 0) pageSet.add(p);
        for (MediaRef m : art.media) if (m.page > 0) pageSet.add(m.page);
        art.pages.addAll(pageSet);
        chapter.articles.add(art);
    }

    static void postProcessAppendix(Chapter appendix) {
        List<Article> cleaned = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Article a : appendix.articles) {
            if (a.id.startsWith("\u5716-") || a.id.startsWith("\u8868-")) continue;
            if (isNumericGarbage(a.body) && a.media.isEmpty()) continue;
            if (!seen.add(a.id)) continue;
            cleaned.add(a);
        }
        cleaned.sort((a, b) -> {
            int ra = appendixRank(a.id);
            int rb = appendixRank(b.id);
            if (ra != rb) return Integer.compare(ra, rb);
            return a.id.compareTo(b.id);
        });
        appendix.articles.clear();
        appendix.articles.addAll(cleaned);
    }

    static void fixAppendixSectionPages(Chapter appendix) {
        Map<String, Integer> sectionPage = new HashMap<>();
        for (Article a : appendix.articles) {
            if (!a.id.startsWith("A") || a.pages.isEmpty()) continue;
            int p = Collections.min(a.pages);
            Matcher m = Pattern.compile("^(A\\d{3})").matcher(a.id);
            if (m.find()) sectionPage.merge(m.group(1), p, Math::min);
        }
        for (Article a : appendix.articles) {
            if (!a.id.matches("A\\d{3}")) continue;
            int childPage = sectionPage.getOrDefault(a.id, -1);
            if (childPage < 0) continue;
            int cur = a.pages.isEmpty() ? 0 : Collections.min(a.pages);
            if (cur < 80) {
                a.pages.clear();
                a.pages.add(childPage);
            }
        }
    }

    static Article findArticleInChapter(Chapter ch, String artId) {
        for (Article x : ch.articles) if (x.id.equals(artId)) return x;
        return null;
    }

    static void attachChildArticleMedia(List<LineBox> allLines, Chapter ch, Article a, String flat, int artPage) {
        Pattern subArtRefPat = Pattern.compile(
                "(?:\u5982|\u8981|\u898B)(A\\d+(?:\\.\\d+)+)|(?:\u53CA|\u3001|\u6216|\u548C)(A\\d+(?:\\.\\d+)+)");
        Matcher sm = subArtRefPat.matcher(flat);
        while (sm.find()) {
            String refId = sm.group(1) != null ? sm.group(1) : sm.group(2);
            if (refId == null || !refId.startsWith(a.id + ".")) continue;
            Article child = findArticleInChapter(ch, refId);
            if (child == null) continue;
            for (MediaRef cm : child.media) {
                addReferencedMedia(allLines, a, cm.ref, cm.type, artPage);
            }
        }
    }

    static void attachReferencedMedia(List<LineBox> allLines, List<Chapter> chapters) {
        Pattern figPat = Pattern.compile(
                "(?:\u5982\u5716|\\(\u5982\u5716|\uFF08\u5982\u5716|\\u3001\u5982\u5716)\\s*(\\d{3,4}(?:\\.\\d+)*|[A]\\d+(?:\\.\\d+)*)");
        Pattern tblPat = Pattern.compile(
                "(?:\u5982\u8868|\\(\u5982\u8868|\uFF08\u5982\u8868|\\u3001\u5982\u8868)\\s*(\\d{3,4}(?:\\.\\d+)*|[A]\\d+(?:\\.\\d+)*)");
        Pattern figListPat = Pattern.compile("(?:\u3001|\u53CA|\uFF0C|,\\s*)\u5716(\\d{3,4}(?:\\.\\d+)*)");
        for (Chapter ch : chapters) {
            for (Article a : ch.articles) {
                if (a.id.startsWith("\u5716-") || a.id.startsWith("\u8868-")) continue;
                int artPage = findArticlePdfPage(allLines, a.id);
                if (artPage <= 0 && !a.pages.isEmpty()) artPage = Collections.min(a.pages);
                String flat = a.body.replace("\n", " ");
                Matcher fm = figPat.matcher(flat);
                while (fm.find()) addReferencedMedia(allLines, a, fm.group(1), "figure", artPage);
                Matcher tm = tblPat.matcher(flat);
                while (tm.find()) addReferencedMedia(allLines, a, tm.group(1), "table", artPage);
                Matcher fl = figListPat.matcher(flat);
                while (fl.find()) addReferencedMedia(allLines, a, fl.group(1), "figure", artPage);
                attachChildArticleMedia(allLines, ch, a, flat, artPage);
            }
        }
    }

    static void addReferencedMedia(List<LineBox> allLines, Article a, String ref, String type, int artPage) {
        if (a.media.stream().anyMatch(x -> ref.equals(x.ref) && type.equals(x.type))) return;
        MediaRef mr = new MediaRef();
        mr.type = type;
        mr.ref = ref;
        mr.caption = ("figure".equals(type) ? "\u5716" : "\u8868") + ref;
        mr.page = resolveMediaPdfPage(allLines, mr, a.id, artPage);
        if (mr.page > 0) a.media.add(mr);
    }

    static int appendixRank(String id) {
        if (id.startsWith("APP-")) return Integer.parseInt(id.substring(4)) * 1000;
        if (id.startsWith("A")) return 5000;
        if (id.startsWith("\u5716-") || id.startsWith("\u8868-")) return 9000;
        return 7000;
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

    static int findCaptionPdfPage(List<LineBox> allLines, MediaRef m) {
        int best = -1, bestScore = 0;
        for (LineBox lb : allLines) {
            int score = captionScore(lb.text, m.ref, m.type);
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

    /** Figure may stay on article page, or appear on the next page with its caption. */
    static int resolveMediaPdfPage(List<LineBox> allLines, MediaRef m, String artId, int artPage) {
        int captionPage = findCaptionPdfPage(allLines, m);
        if (captionPage <= 0) return artPage > 0 ? artPage : -1;

        String ownerId = m.ref;
        int ownerArtPage = findArticlePdfPage(allLines, ownerId);
        if (ownerArtPage > 0) {
            artPage = ownerArtPage;
            artId = ownerId;
        }

        if (captionPage == artPage) return captionPage;
        if (artPage > 0 && captionPage == artPage + 1) {
            if (hasOtherArticleStartsOnPage(allLines, artPage, artId)) return captionPage;
            return artPage;
        }
        return captionPage;
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

    static void postProcessPdfPages(List<LineBox> allLines, List<Chapter> chapters) {
        for (Chapter ch : chapters) {
            for (Article a : ch.articles) {
                int artPage = a.id.startsWith("\u5716-") || a.id.startsWith("\u8868-") || a.id.startsWith("APP-")
                        ? -1 : findArticlePdfPage(allLines, a.id);
                for (MediaRef m : a.media) {
                    int p = resolveMediaPdfPage(allLines, m, a.id, artPage);
                    if (p > 0) m.page = p;
                }
                if (a.id.startsWith("\u5716-") || a.id.startsWith("\u8868-")) {
                    if (!a.media.isEmpty() && a.media.get(0).page > 0) {
                        a.pages.clear();
                        a.pages.add(a.media.get(0).page);
                    }
                    continue;
                }
                LinkedHashSet<Integer> pages = new LinkedHashSet<>();
                if (artPage <= 0) artPage = findArticlePdfPage(allLines, a.id);
                if (artPage > 0) pages.add(artPage);
                for (MediaRef m : a.media) if (m.page > 0) pages.add(m.page);
                if (!pages.isEmpty()) {
                    a.pages.clear();
                    a.pages.addAll(pages);
                }
            }
        }
    }

    static Path findPdftoppm() {
        String[] candidates = {
                "pdftoppm",
                "C:\\Program Files\\poppler\\Library\\bin\\pdftoppm.exe",
                "C:\\poppler\\Library\\bin\\pdftoppm.exe"
        };
        for (String c : candidates) {
            try {
                Process p = new ProcessBuilder(c, "-v").redirectErrorStream(true).start();
                if (p.waitFor() == 0 || p.waitFor() == 99) return Paths.get(c);
            } catch (Exception ignored) {}
        }
        throw new RuntimeException("pdftoppm not found");
    }

    static BufferedImage renderPagePoppler(Path pdf, Path pdftoppm, Path tmp, int pageNum, int dpi) throws Exception {
        String prefix = tmp.resolve("p").toString();
        ProcessBuilder pb = new ProcessBuilder(
                pdftoppm.toString(), "-png", "-r", String.valueOf(dpi),
                "-f", String.valueOf(pageNum), "-l", String.valueOf(pageNum),
                "-singlefile", pdf.toString(), prefix);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            while (br.readLine() != null) {}
        }
        if (proc.waitFor() != 0) throw new IOException("pdftoppm failed page " + pageNum);
        Path png = tmp.resolve("p.png");
        if (!Files.exists(png)) throw new IOException("Missing " + png);
        return ImageIO.read(png.toFile());
    }

    static void writePng(BufferedImage image, Path out) throws IOException {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        ImageIO.write(rgb, "png", out.toFile());
    }

    static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path pdfFile = root.resolve("assets/accessibility-spec.pdf");
        Path pagesDir = root.resolve("assets/accessibility-spec/pages");
        Path textFile = root.resolve("scripts/accessibility-spec-text.txt");
        Path outFile = root.resolve("js/accessibility-spec.js");
        int pageDpi = 144;
        Path pdftoppm = findPdftoppm();
        Path renderTmp = Files.createTempDirectory("access-render-");
        System.out.println("Image renderer: poppler " + pdftoppm);

        Files.createDirectories(pagesDir);

        List<LineBox> allLines = new ArrayList<>();

        try (PDDocument doc = Loader.loadPDF(pdfFile.toFile())) {
            PageLinesStripper stripper = new PageLinesStripper();
            int total = doc.getNumberOfPages();
            System.out.println("PDF pages: " + total);

            for (int i = 0; i < total; i++) {
                int pageNum = i + 1;
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                stripper.getText(doc);
                allLines.addAll(stripper.lines);
                stripper.lines.clear();
            }

            Map<Integer, Integer> printedToPdf = buildPrintedToPdfMap(doc, allLines);
            System.out.println("printedToPdf entries: " + printedToPdf.size());

            for (int i = 0; i < total; i++) {
                int pageNum = i + 1;
                Path out = pagesDir.resolve(String.format("page-%03d.png", pageNum));
                BufferedImage image = renderPagePoppler(pdfFile, pdftoppm, renderTmp, pageNum, pageDpi);
                writePng(image, out);
            }

            List<String> lines = Files.readAllLines(textFile, StandardCharsets.UTF_8);
            int startIdx = 0;
            int firstChapterHits = 0;
            Pattern firstChPat = Pattern.compile("\u7b2c\u4e00\u7ae0\\s+\u7e3d\u5247");
            for (int i = 0; i < lines.size(); i++) {
                if (firstChPat.matcher(lines.get(i)).find()) {
                    firstChapterHits++;
                    if (firstChapterHits == 2) { startIdx = i; break; }
                }
            }

            int defaultPdfPage = printedToPdf.getOrDefault(1, 8);
            int markerScanStart = startIdx;
            if (startIdx > 0 && isPageMarker(lines.get(startIdx - 1).trim())) markerScanStart = startIdx - 1;
            List<int[]> markerEvents = buildMarkerEvents(lines, markerScanStart, printedToPdf);
            System.out.println("startIdx=" + startIdx + " defaultPdf=" + defaultPdfPage
                    + " markerEvents=" + markerEvents.size());

            Pattern chPat = Pattern.compile("^\u7b2c([\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u5341]+)\u7ae0\\s*(.+)$");
            Pattern mainArtPat = Pattern.compile("^(\\d{3,4})\\s+(.+)$");
            Pattern subArtPat = Pattern.compile("^(\\d{3,4}\\.\\d+(?:\\.\\d+)?)\\s+(.+)$");
            Pattern appendixArtPat = Pattern.compile("^(A\\d{3}(?:\\.\\d+(?:\\.\\d+)?)?)\\s+(.+)$");
            Pattern appendixStartPat = Pattern.compile("^\u9644\u9304\\s*\\d+\\s+.*$");

            List<Chapter> chapters = new ArrayList<>();
            Chapter currentChapter = null;
            Article currentArt = null;
            List<String> bodyLines = new ArrayList<>();
            int currentPdfPage = pageAtLine(startIdx, markerEvents, defaultPdfPage);
            int artStartPage = currentPdfPage;
            boolean skipTableRows = false;

            for (int i = startIdx; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                currentPdfPage = pageAtLine(i, markerEvents, defaultPdfPage);
                if (isPageMarker(line)) continue;

                Matcher chM = chPat.matcher(line);
                if (chM.matches()) {
                    flushArticle(currentChapter, currentArt, bodyLines, artStartPage, currentPdfPage);
                    if (currentChapter != null && !currentChapter.articles.isEmpty()) chapters.add(currentChapter);
                    String title = clean(chM.group(2));
                    int num = chineseChapterNum(chM.group(1));
                    currentChapter = new Chapter();
                    currentChapter.id = "ACCESS-ch" + num;
                    currentChapter.icon = chapterIcon(title);
                    currentChapter.title = title;
                    currentChapter.subtitle = clean(line);
                    currentChapter.desc = currentChapter.subtitle;
                    currentArt = null;
                    bodyLines.clear();
                    continue;
                }

                if (appendixStartPat.matcher(line).matches()) {
                    flushArticle(currentChapter, currentArt, bodyLines, artStartPage, currentPdfPage);
                    if (currentChapter == null || !"ACCESS-appendix".equals(currentChapter.id)) {
                        if (currentChapter != null && !currentChapter.articles.isEmpty()) chapters.add(currentChapter);
                        currentChapter = new Chapter();
                        currentChapter.id = "ACCESS-appendix";
                        currentChapter.icon = "\uD83D\uDCCA";
                        currentChapter.title = "\u53c3\u8003\u9644\u9304";
                        currentChapter.subtitle = "\u53c3\u8003\u9644\u9304";
                        currentChapter.desc = "\u53c3\u8003\u9644\u9304";
                        currentArt = null;
                        bodyLines.clear();
                    }
                    String[] appSec = parseAppendixSection(line);
                    if (appSec != null) {
                        flushArticle(currentChapter, currentArt, bodyLines, artStartPage, currentPdfPage);
                        currentArt = new Article();
                        currentArt.id = appSec[0];
                        currentArt.title = appSec[1];
                        bodyLines.clear();
                        artStartPage = currentPdfPage;
                    }
                    continue;
                }

                boolean appendix = currentChapter != null && "ACCESS-appendix".equals(currentChapter.id);

                if (appendix && isFigureLine(line)) {
                    MediaRef mr = new MediaRef();
                    mr.type = mediaType(line);
                    mr.ref = mediaRef(line);
                    mr.caption = ("table".equals(mr.type) ? "\u8868" : "\u5716") + mr.ref;
                    mr.page = currentPdfPage;
                    if (currentArt != null && !currentArt.id.startsWith("\u5716-")
                            && !currentArt.id.startsWith("\u8868-") && !currentArt.id.startsWith("APP-")) {
                        if (currentArt.media.stream().noneMatch(x -> x.ref.equals(mr.ref))) {
                            currentArt.media.add(mr);
                        }
                        bodyLines.add(line);
                        skipTableRows = "table".equals(mr.type);
                    } else {
                        flushArticle(currentChapter, currentArt, bodyLines, artStartPage, currentPdfPage);
                        currentArt = new Article();
                        currentArt.id = mediaArtId(line);
                        currentArt.title = mr.caption;
                        bodyLines.clear();
                        artStartPage = currentPdfPage;
                        currentArt.media.add(mr);
                        bodyLines.add(line);
                        skipTableRows = "table".equals(mr.type);
                    }
                    continue;
                }

                Matcher subM = subArtPat.matcher(line);
                Matcher mainM = mainArtPat.matcher(line);
                Matcher appArtM = appendixArtPat.matcher(line);

                if (!appendix && subM.matches()) {
                    flushArticle(currentChapter, currentArt, bodyLines, artStartPage, currentPdfPage);
                    currentArt = new Article();
                    currentArt.id = subM.group(1);
                    currentArt.title = clean(subM.group(2));
                    bodyLines.clear();
                    artStartPage = currentPdfPage;
                    if (subM.group(2).length() > 20) bodyLines.add(subM.group(2));
                    continue;
                }

                if (!appendix && mainM.matches() && !line.matches("\\d{3,4}\\.\\d+.*")) {
                    flushArticle(currentChapter, currentArt, bodyLines, artStartPage, currentPdfPage);
                    currentArt = new Article();
                    currentArt.id = mainM.group(1);
                    currentArt.title = clean(mainM.group(2));
                    bodyLines.clear();
                    artStartPage = currentPdfPage;
                    if (mainM.group(2).length() > 20) bodyLines.add(mainM.group(2));
                    continue;
                }

                if (appendix && appArtM.matches()) {
                    flushArticle(currentChapter, currentArt, bodyLines, artStartPage, currentPdfPage);
                    currentArt = new Article();
                    currentArt.id = appArtM.group(1);
                    currentArt.title = clean(appArtM.group(2));
                    bodyLines.clear();
                    artStartPage = currentPdfPage;
                    if (appArtM.group(2).length() > 20) bodyLines.add(appArtM.group(2));
                    continue;
                }

                if (appendix && isAppendixArticle(line)) {
                    if (currentArt == null) {
                        currentArt = new Article();
                        currentArt.id = "A-text";
                        currentArt.title = "\u9644\u9304\u8aaa\u660e";
                        artStartPage = currentPdfPage;
                    }
                    bodyLines.add(line);
                    skipTableRows = false;
                    continue;
                }

                if (currentArt != null) {
                    if (!appendix && isFigureLine(line)) {
                        MediaRef mr = new MediaRef();
                        mr.type = mediaType(line);
                        mr.ref = mediaRef(line);
                        mr.caption = clean(line);
                        mr.page = currentPdfPage;
                        currentArt.media.add(mr);
                        bodyLines.add(line);
                        skipTableRows = "table".equals(mr.type);
                        continue;
                    }
                    if (skipTableRows) {
                        if (isFigureLine(line) || subM.reset(line).matches() || mainM.reset(line).matches()
                                || parseAppendixSection(line) != null || appArtM.reset(line).matches()) {
                            skipTableRows = false;
                        } else if (isNumericGarbage(line)) {
                            continue;
                        }
                    }
                    if (appendix && isNumericGarbage(line)) continue;
                    bodyLines.add(line);
                }
            }
            flushArticle(currentChapter, currentArt, bodyLines, artStartPage, currentPdfPage);
            if (currentChapter != null && !currentChapter.articles.isEmpty()) chapters.add(currentChapter);

            for (Chapter ch : chapters) {
                if ("ACCESS-appendix".equals(ch.id)) postProcessAppendix(ch);
            }
            attachReferencedMedia(allLines, chapters);
            postProcessPdfPages(allLines, chapters);
            for (Chapter ch : chapters) {
                if ("ACCESS-appendix".equals(ch.id)) fixAppendixSectionPages(ch);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("/** Accessibility spec: text + PDF page links */\n");
            sb.append("const ACCESSIBILITY_SPEC_LAW = {\n");
            sb.append("  id: 'ACCESSIBILITY-SPEC',\n");
            sb.append("  pcode: null,\n");
            sb.append("  name: '\u5efa\u7bc9\u7269\u7121\u969c\u7919\u8a2d\u65bd\u8a2d\u8a08\u898f\u7bc4',\n");
            sb.append("  shortName: '\u7121\u969c\u7919\u8a2d\u8a08\u898f\u7bc4',\n");
            sb.append("  icon: '\u267F',\n");
            sb.append("  amended: '\u6c11\u570b 108 \u5e74 7 \u6708 1 \u65e5',\n");
            sb.append("  fullUrl: null,\n");
            sb.append("  source: 'pdf',\n");
            sb.append("  pdfFile: 'assets/accessibility-spec.pdf',\n");
            sb.append("  pageDir: 'assets/accessibility-spec/pages',\n");
            sb.append("  pageCount: ").append(total).append(",\n");
            sb.append("  categories: [\n");

            for (int ci = 0; ci < chapters.size(); ci++) {
                Chapter ch = chapters.get(ci);
                sb.append("  {\n");
                sb.append("    id: '").append(ch.id).append("',\n");
                sb.append("    icon: '").append(ch.icon).append("',\n");
                sb.append("    title: '").append(escapeJs(ch.title)).append("',\n");
                sb.append("    subtitle: '").append(escapeJs(ch.subtitle)).append("',\n");
                sb.append("    desc: '").append(escapeJs(ch.desc)).append("',\n");
                sb.append("    articles: [\n");
                for (int ai = 0; ai < ch.articles.size(); ai++) {
                    Article a = ch.articles.get(ai);
                    sb.append("      { art: '").append(a.id).append("', label: '")
                            .append(escapeJs(a.label)).append("', body: '")
                            .append(escapeJs(a.body)).append("', pages: [")
                            .append(a.pages.stream().map(String::valueOf).reduce((x, y) -> x + ", " + y).orElse(""))
                            .append("], media: [");
                    for (int mi = 0; mi < a.media.size(); mi++) {
                        MediaRef m = a.media.get(mi);
                        sb.append("{ type: '").append(m.type).append("', ref: '")
                                .append(escapeJs(m.ref)).append("', caption: '")
                                .append(escapeJs(m.caption)).append("', page: ")
                                .append(m.page).append(" }");
                        if (mi < a.media.size() - 1) sb.append(", ");
                    }
                    sb.append("] }");
                    if (ai < ch.articles.size() - 1) sb.append(",");
                    sb.append("\n");
                }
                sb.append("    ],\n");
                sb.append("  }");
                if (ci < chapters.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("  ],\n");
            sb.append("};\n");

            Files.write(outFile, sb.toString().getBytes(StandardCharsets.UTF_8));
            int totalArts = chapters.stream().mapToInt(c -> c.articles.size()).sum();
            System.out.println("Chapters: " + chapters.size() + ", Articles: " + totalArts);
            System.out.println("Output: " + outFile);
        } finally {
            deleteTree(renderTmp);
        }
    }
}
