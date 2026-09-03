import java.io.IOException;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

public class FindCaption {
    static String clean(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    public static void main(String[] args) throws Exception {
        String ref = args[1];
        List<String> hits = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(new java.io.File(args[0]))) {
            PDFTextStripper stripper = new PDFTextStripper() {
                int activePage = 0;
                @Override protected void startPage(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
                    super.startPage(page);
                    activePage = getCurrentPageNo();
                }
                @Override protected void writeString(String string, List<TextPosition> textPositions) {
                    String t = clean(string);
                    String compact = t.replaceAll("\\s+", "");
                    if (compact.contains("\u5716" + ref) || compact.equals("\u5716" + ref)) {
                        hits.add("PDF " + activePage + " line=[" + t + "]");
                    }
                }
            };
            stripper.getText(doc);
        }
        for (String h : hits) System.out.println(h);
        if (hits.isEmpty()) System.out.println("No caption lines for \u5716" + ref);
    }
}
