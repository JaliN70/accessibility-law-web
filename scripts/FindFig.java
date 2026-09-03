import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

public class FindFig {
    public static void main(String[] args) throws Exception {
        try (PDDocument doc = Loader.loadPDF(new java.io.File(args[0]))) {
            PDFTextStripper s = new PDFTextStripper();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                s.setStartPage(i);
                s.setEndPage(i);
                String t = s.getText(doc).replaceAll("\\s+", "");
                if (t.contains("504.4.1")) {
                    boolean fig = t.contains("\u5716504.4.1") || t.matches(".*\u5716\\s*504\\.4\\.1.*");
                    System.out.println("PDF page " + i + (fig ? " [FIGURE]" : " [TEXT]"));
                }
            }
        }
    }
}
