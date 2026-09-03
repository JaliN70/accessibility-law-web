import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

public class ScanPages {
    public static void main(String[] args) throws Exception {
        int lo = Integer.parseInt(args[1]);
        int hi = Integer.parseInt(args[2]);
        try (PDDocument doc = Loader.loadPDF(new java.io.File(args[0]))) {
            PDFTextStripper s = new PDFTextStripper();
            for (int i = lo; i <= hi; i++) {
                s.setStartPage(i);
                s.setEndPage(i);
                String t = s.getText(doc).replaceAll("[\\s\\u00A0]+", " ").trim();
                if (t.length() > 120) t = t.substring(0, 120) + "...";
                System.out.println("=== PDF " + i + " ===");
                System.out.println(t);
            }
        }
    }
}
