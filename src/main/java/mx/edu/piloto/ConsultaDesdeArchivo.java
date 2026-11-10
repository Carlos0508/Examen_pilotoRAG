package mx.edu.piloto;
import java.nio.file.*;
/** Transporte de preguntas por archivo: conserva comillas y Unicode en Windows. */
public final class ConsultaDesdeArchivo {
 public static void main(String[] args)throws Exception{
  if(args.length!=3)throw new IllegalArgumentException("Uso: <indice> <pregunta.txt> <salida>");
  RagMultidocumento.main(new String[]{args[0],Files.readString(Path.of(args[1])),args[2]});
 }
}
