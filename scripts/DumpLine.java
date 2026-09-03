import java.text.Normalizer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import java.util.List;

public class DumpLine {
    static String clean(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).replaceAll("[\\s\\u00A0]+", " ").trim();
    }
    public static void main(String[] args) throws Exception {
        try (var doc = Loader.loadPDF(new java.io.File(args[0]))) {
            PDFTextStripper s = new PDFTextStripper() {
                int p = 0;
                protected void startPage(org.apache.pdfbox.pdmodel.PDPage page) { p = getCurrentPageNo(); }
                protected void writeString(String str, List<TextPosition> tp) {
                    if ((p == 7 || p == 100) && clean(str).contains("A404")) {
                        System.out.println("PDF " + p + " len=" + clean(str).length() + " [" + clean(str) + "]");
                    }
                }
            };
            s.getText(doc);
        }
    }
}
