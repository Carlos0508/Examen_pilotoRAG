package mx.edu.piloto;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Extrae pixeles de una region para OCR; conserva la imagen original y el desplazamiento. */
public final class ExtraerRegionOcr {
 public static void main(String[] args)throws Exception{
  if(args.length!=7)throw new IllegalArgumentException("Uso: <imagen> <pagina> <x> <y> <ancho> <alto> <salida-nueva>");
  Path source=Path.of(args[0]).toRealPath(),out=Path.of(args[6]).toAbsolutePath().normalize();
  int page=Integer.parseInt(args[1]),x=Integer.parseInt(args[2]),y=Integer.parseInt(args[3]),w=Integer.parseInt(args[4]),h=Integer.parseInt(args[5]);
  if(Files.exists(out))throw new IllegalArgumentException("Salida existente");var original=ImageIO.read(source.toFile());
  if(original==null||page<1||x<0||y<0||w<=0||h<=0||(long)x+w>original.getWidth()||(long)y+h>original.getHeight())throw new IllegalArgumentException("Region fuera de imagen");
  Files.createDirectories(out);var crop=original.getSubimage(x,y,w,h);if(!ImageIO.write(crop,"png",out.resolve("region.png").toFile()))throw new IllegalStateException("PNG no disponible");
  String record="{\"pagina\":"+page+",\"imagen_origen\":"+OcrPagina.json(source.toString())+",\"imagen_sha256\":"+OcrPagina.json(OcrPagina.hash(source))+",\"region_sha256\":"+OcrPagina.json(OcrPagina.hash(out.resolve("region.png")))+",\"bbox_origen_px\":["+x+","+y+","+w+","+h+"],\"transformacion\":\"x_pagina=x_region+"+x+", y_pagina=y_region+"+y+"\",\"operacion\":\"EXTRACCION_PIXELES_SIN_REESCALADO\"}\n";
  Files.writeString(out.resolve("origen-region.json"),record,StandardCharsets.UTF_8);crop.flush();original.flush();
  System.out.println("Region extraida: "+out);
 }
}
