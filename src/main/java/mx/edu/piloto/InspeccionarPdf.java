package mx.edu.piloto;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.*;
import org.apache.pdfbox.text.PDFTextStripper;
public final class InspeccionarPdf {
 public static void main(String[] args) throws Exception {
  if(args.length!=2) throw new IllegalArgumentException("Uso: InspeccionarPdf <pdf> <carpeta-nueva>");
  Path input=Path.of(args[0]).toRealPath(), output=Path.of(args[1]).toAbsolutePath().normalize();
  if(Files.exists(output)) throw new IllegalArgumentException("La salida ya existe: "+output);
  Files.createDirectories(output);
  MessageDigest digest=MessageDigest.getInstance("SHA-256");
  try(var stream=Files.newInputStream(input)) {
   byte[] buffer=new byte[65536];
   for(int n;(n=stream.read(buffer))!=-1;) digest.update(buffer,0,n);
  }
  Files.writeString(output.resolve("origen.txt"),"archivo="+input+"\nsha256="+HexFormat.of().formatHex(digest.digest())+"\npdfbox=3.0.8\ndpi=300\n",StandardCharsets.UTF_8);
  StringBuilder csv=new StringBuilder("pagina,caracteres_no_blancos,estado,imagen_ocr\n");
  try(PDDocument doc=Loader.loadPDF(input.toFile())) {
   if(!doc.getCurrentAccessPermission().canExtractContent()) throw new IllegalArgumentException("Extraccion no permitida");
   PDFTextStripper stripper=new PDFTextStripper(); stripper.setSortByPosition(true);
   PDFRenderer renderer=new PDFRenderer(doc);
   for(int i=0;i<doc.getNumberOfPages();i++) {
    int page=i+1; String prefix="pagina-%03d".formatted(page);
    stripper.setStartPage(page); stripper.setEndPage(page);
    String raw=stripper.getText(doc);
    long count=raw.codePoints().filter(c->!Character.isWhitespace(c)&&!Character.isSpaceChar(c)).count();
    Files.writeString(output.resolve(prefix+"-extraido.txt"),raw,StandardCharsets.UTF_8);
    String state=count==0?"SIN_TEXTO_REVISAR_OCR":count<100?"TEXTO_ESCASO_REVISAR":"CON_TEXTO_REVISAR_CALIDAD";
    String png="";
    if(count<100) {
     png=prefix+".png"; var image=renderer.renderImageWithDPI(i,300,ImageType.RGB);
     try { if(!ImageIO.write(image,"png",output.resolve(png).toFile())) throw new IllegalStateException("Sin codificador PNG"); }
     finally {image.flush();}
    }
    csv.append(page).append(',').append(count).append(',').append(state).append(',').append(png).append('\n');
    System.out.println("Pagina "+page+": "+state+" ("+count+" caracteres)");
   }
  }
  Files.writeString(output.resolve("diagnostico.csv"),csv,StandardCharsets.UTF_8);
 }
}
