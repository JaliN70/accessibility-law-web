import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

public class FindRef {
    public static void main(String[] args) throws Exception {
        String ref = args[1];
        try (PDDocument doc = Loader.loadPDF(new java.io.File(args[0]))) {
            PDFTextStripper s = new PDFTextStripper();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                s.setStartPage(i);
                s.setEndPage(i);
                String raw = s.getText(doc);
                String t = raw.replaceAll("\\s+", "");
                if (t.contains(ref.replace(".", "\\.")) || raw.contains(ref)) {
                    boolean fig = raw.contains("\u5716" + ref) || t.contains("\u5716" + ref.replace(".", ""));
                    System.out.println("PDF " + i + (fig ? " [FIG]" : " [text]") + " | " + raw.replaceAll("[\\s\\u00A0]+", " ").trim().substring(0, Math.min(100, raw.length())));
                }
            }
        }
    }
}
