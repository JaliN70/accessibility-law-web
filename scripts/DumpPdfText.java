import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class DumpPdfText {
    public static void main(String[] args) throws Exception {
        Path pdf = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper s = new PDFTextStripper();
            s.setSortByPosition(true);
            String text = s.getText(doc);
            Files.writeString(out, text, StandardCharsets.UTF_8);
            System.out.println("Pages: " + doc.getNumberOfPages());
            System.out.println("Wrote: " + out);
        }
    }
}
