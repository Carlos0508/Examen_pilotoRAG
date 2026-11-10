package mx.edu.piloto;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Puntero transaccional; las versiones previas nunca se sobrescriben. */
public final class RegistroAcervo {
 static Path active(Path root)throws Exception{
  Path pointer=root.resolve("acervo-activo.json");if(!Files.exists(pointer))return root.resolve("indice-multidocumento-07/indice.json").toRealPath();
  var p=CorpusMultidocumento.read(pointer);Path index=root.resolve(p.get("indice").getAsString()).normalize().toRealPath();
  if(!index.startsWith(root.toRealPath())||!OcrPagina.hash(index).equals(p.get("sha256").getAsString()))throw new IllegalArgumentException("Puntero de índice inválido");return index;
 }
 static void atomic(Path target,JsonObject data)throws Exception{
  Path temp=target.resolveSibling(target.getFileName()+"."+UUID.randomUUID()+".tmp");
  Files.writeString(temp,CorpusMultidocumento.JSON.toJson(data));
  try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.deleteIfExists(temp);throw new IllegalStateException("El disco no permite publicación atómica; se conserva el índice anterior",e);}
 }
 static void activate(Path root,Path index)throws Exception{
  if(QdrantLocal.enabled(root))QdrantLocal.importIndex(root,index);
  var data=new JsonObject();data.addProperty("indice",root.toRealPath().relativize(index.toRealPath()).toString());data.addProperty("sha256",OcrPagina.hash(index));data.addProperty("actualizado",java.time.Instant.now().toString());atomic(root.resolve("acervo-activo.json"),data);
 }
 static List<JsonObject> additions(JsonObject index)throws Exception{
  Path m=Path.of(index.get("manifiesto").getAsString());var manifest=CorpusMultidocumento.read(m);List<JsonObject> files=new ArrayList<>();
  if(manifest.has("altas"))for(var r:manifest.getAsJsonArray("altas")){Path cfg=CorpusMultidocumento.verified(m.getParent(),r.getAsJsonObject());files.add(CorpusMultidocumento.read(cfg));}return files;
 }
 public static void main(String[] args)throws Exception{System.out.println(active(Path.of(args[0]).toRealPath()));}
}
