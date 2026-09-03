import java.io.IOException;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

public class FindArtLines {
    static String clean(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    public static void main(String[] args) throws Exception {
        String artId = args[1];
        Pattern pat = Pattern.compile("^" + Pattern.quote(artId) + "(?:\\s|[\uFF1A:]|$)");
        try (PDDocument doc = Loader.loadPDF(new java.io.File(args[0]))) {
            PDFTextStripper stripper = new PDFTextStripper() {
                int activePage = 0;
                @Override protected void startPage(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
                    super.startPage(page);
                    activePage = getCurrentPageNo();
                }
                @Override protected void writeString(String string, List<TextPosition> textPositions) {
                    String t = clean(string);
                    if (pat.matcher(t).find() || t.contains(artId)) {
                        System.out.println("PDF " + activePage + " | " + t.substring(0, Math.min(80, t.length())));
                    }
                }
            };
            stripper.getText(doc);
        }
    }
}
