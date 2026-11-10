package mx.edu.piloto;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Servidor de un usuario, limitado a loopback y una consulta simultánea. */
public final class InterfazLocal {
 final Path root; final String token=UUID.randomUUID().toString();
 final Semaphore busy=new Semaphore(1); final Map<String,Path> pdfs=new ConcurrentHashMap<>();
 final Map<String,String> hashes=new ConcurrentHashMap<>(); volatile JsonArray catalog=new JsonArray(); volatile int fragmentCount; volatile Path activeIndex;
 InterfazLocal(Path dir)throws Exception{
  root=dir.toRealPath();Files.createDirectories(root.resolve("entrada-circulares"));Files.createDirectories(root.resolve("altas/trabajos"));refresh();
 }
 synchronized void refresh()throws Exception{
  JsonArray catalog=new JsonArray();
  var cat=JsonParser.parseString(Files.readString(root.resolve("corpus/acervo-completo-01/catalogo-cobertura.json"))).getAsJsonObject();
  for(var e:cat.getAsJsonArray("fuentes")){
   var f=e.getAsJsonObject();String id=f.get("fuente_id").getAsString();Path p=Path.of(f.get("fuente_pdf").getAsString()).toRealPath();
   if(!p.startsWith(root))throw new IllegalArgumentException("PDF fuera del piloto");
   if(!OcrPagina.hash(p).equals(f.get("sha256").getAsString()))throw new IllegalArgumentException("PDF modificado: "+id);
   pdfs.put(id,p);hashes.put(f.get("sha256").getAsString(),id);
   var item=new JsonObject();for(String k:List.of("fuente_id","archivo","archivos_equivalentes","paginas_total","clave_propuesta"))item.add(k,f.get(k));
   JsonArray pages=new JsonArray();for(var page:f.getAsJsonArray("paginas")){var o=new JsonObject();o.add("pagina",page.getAsJsonObject().get("pagina"));o.add("estado",page.getAsJsonObject().get("estado"));pages.add(o);}item.add("paginas",pages);catalog.add(item);
  }
  activeIndex=RegistroAcervo.active(root);var index=CorpusMultidocumento.read(activeIndex);
  for(var cfg:RegistroAcervo.additions(index)){var item=new JsonObject();String id=cfg.get("fuente_id").getAsString();Path pdf=CorpusMultidocumento.verified(Path.of("."),cfg.getAsJsonObject("pdf"));
   if(!pdf.startsWith(root))throw new IllegalArgumentException("Alta fuera del piloto");pdfs.put(id,pdf);hashes.put(cfg.getAsJsonObject("pdf").get("sha256").getAsString(),id);
   item.addProperty("fuente_id",id);item.add("archivo",cfg.get("archivo"));item.add("clave_propuesta",cfg.get("clave_propuesta"));item.add("archivos_equivalentes",CorpusMultidocumento.JSON.toJsonTree(List.of(cfg.get("archivo").getAsString())));item.add("paginas_total",cfg.get("paginas_total"));JsonArray pages=new JsonArray();
   for(var e:cfg.getAsJsonArray("paginas")){var p=e.getAsJsonObject();var page=new JsonObject();page.add("pagina",p.get("pagina"));page.addProperty("estado",p.get("sin_texto").getAsBoolean()?"SIN_TEXTO_EXTRAIBLE_REVISAR_PDF":"INDEXABLE_AUTOMATICO_NO_REVISADO");pages.add(page);}item.add("paginas",pages);catalog.add(item);
  }
  fragmentCount=index.getAsJsonArray("entradas").size();
  for(var item:catalog){var source=item.getAsJsonObject();Set<String> keys=new TreeSet<>();
   for(var entry:index.getAsJsonArray("entradas")){var c=entry.getAsJsonObject().getAsJsonObject("fragmento");
    if(c.has("fuente_pdf_sha256")&&source.get("fuente_id").getAsString().equals(hashes.get(c.get("fuente_pdf_sha256").getAsString())))
     for(String k:List.of("clave","clave_padre"))if(c.has(k)&&!c.get(k).isJsonNull())keys.add(c.get(k).getAsString());
   }var array=new JsonArray();keys.forEach(array::add);source.add("claves_indice",array);
  }
  this.catalog=catalog;
 }
 void send(HttpExchange x,int status,String type,byte[] body)throws Exception{
  var h=x.getResponseHeaders();h.set("Content-Type",type);h.set("Cache-Control","no-store");h.set("X-Content-Type-Options","nosniff");
  h.set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; frame-ancestors 'none'");
  x.sendResponseHeaders(status,body.length);try(var out=x.getResponseBody()){out.write(body);}
 }
 void json(HttpExchange x,int status,JsonElement body)throws Exception{send(x,status,"application/json; charset=utf-8",body.toString().getBytes(StandardCharsets.UTF_8));}
 void error(HttpExchange x,int status,String message)throws Exception{var j=new JsonObject();j.addProperty("error",message);json(x,status,j);}
 void handle(HttpExchange x){try{
  if(!("127.0.0.1:"+x.getLocalAddress().getPort()).equals(x.getRequestHeaders().getFirst("Host"))){error(x,403,"Host no permitido");return;}
  String path=x.getRequestURI().getPath();
  if(path.equals("/api/consulta")){
   if(!x.getRequestMethod().equals("POST")){error(x,405,"Usa POST");return;}
   if(!token.equals(x.getRequestHeaders().getFirst("X-Piloto-Token"))){error(x,403,"Recarga la página para iniciar la sesión");return;}
   byte[] body=x.getRequestBody().readNBytes(8193);if(body.length>8192){error(x,413,"Pregunta demasiado larga");return;}
   String question;try{question=JsonParser.parseString(new String(body,StandardCharsets.UTF_8)).getAsJsonObject().get("pregunta").getAsString().strip();}catch(Exception e){error(x,400,"Pregunta inválida");return;}
   if(question.isEmpty()||question.length()>1000){error(x,400,"Escribe entre 1 y 1000 caracteres");return;}
   if(!busy.tryAcquire()){error(x,409,"Hay una consulta en curso. Espera a que termine.");return;}
   try{
    Path out=root.resolve("consulta-ui-"+UUID.randomUUID().toString().replace("-",""));Path logs=root.resolve("work-interfaz");Files.createDirectories(logs);Path log=logs.resolve(out.getFileName()+".log");Path questionFile=logs.resolve(out.getFileName()+"-pregunta.txt");Files.writeString(questionFile,question);
    var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Xmx512m","-cp",System.getProperty("java.class.path"),"mx.edu.piloto.ConsultaDesdeArchivo",RegistroAcervo.active(root).toString(),questionFile.toString(),out.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try{if(!process.waitFor(10,TimeUnit.MINUTES)){process.destroyForcibly().waitFor();error(x,504,"Consulta demasiado lenta. Revisa Ollama; se conservó el registro.");return;}}catch(InterruptedException e){process.destroyForcibly();Thread.currentThread().interrupt();throw e;}
    if(process.exitValue()!=0){error(x,503,"No se completó la consulta. Revisa Ollama. Registro: "+log.getFileName());return;}
    var result=JsonParser.parseString(Files.readString(out.resolve("respuesta.json"))).getAsJsonObject();
    for(var e:result.getAsJsonArray("citas")){var c=e.getAsJsonObject();String id=c.has("fuente_pdf_sha256")?hashes.get(c.get("fuente_pdf_sha256").getAsString()):null;if(id!=null)c.addProperty("pdf_url","/pdf/"+id+"#page="+c.get("pagina").getAsInt());}
    result.addProperty("carpeta_resultado",out.toString());json(x,200,result);
   }finally{busy.release();}return;
  }
  if(path.equals("/api/subir")||path.equals("/api/procesar")){
   if(!x.getRequestMethod().equals("POST")){error(x,405,"Usa POST");return;}
   if(!token.equals(x.getRequestHeaders().getFirst("X-Piloto-Token"))){error(x,403,"Recarga la página para iniciar la sesión");return;}
   if(!busy.tryAcquire()){error(x,409,"Hay una consulta o incorporación en curso");return;}
   boolean launched=false;try{
    String name="",key="";var params=parameters(x.getRequestURI().getRawQuery());
    if(path.equals("/api/subir")){
     name=params.getOrDefault("nombre","");if(!safeName(name)){error(x,400,"Nombre de PDF inválido");return;}
     key=params.getOrDefault("clave","").strip();if(!key.isEmpty()){
      var canonical=ClavesDocumentales.canonicas(key);if(canonical.size()!=1||!ClavesDocumentales.PATRON.matcher(key).matches()){error(x,400,"Clave no admitida; puedes dejarla vacía");return;}key=canonical.iterator().next();
     }
     byte[] data=x.getRequestBody().readNBytes((int)IncorporarCircular.MAX_BYTES+1);if(data.length>IncorporarCircular.MAX_BYTES){error(x,413,"El PDF supera 30 MB");return;}
     if(data.length<5||!new String(data,0,5,StandardCharsets.US_ASCII).equals("%PDF-")){error(x,400,"El archivo no es un PDF");return;}
     Path input=root.resolve("entrada-circulares").resolve(name);if(Files.exists(input)){error(x,409,"Ya existe ese nombre en la carpeta de entrada. Procesa los pendientes o cambia el nombre");return;}
     Path temp=input.resolveSibling(UUID.randomUUID()+".part");Files.write(temp,data);Files.move(temp,input,StandardCopyOption.ATOMIC_MOVE);
    }
    var job=launch(name,key);launched=true;json(x,202,job);
   }finally{if(!launched)busy.release();}return;
  }
  if(path.equals("/api/trabajo")){
   if(!x.getRequestMethod().equals("GET")){error(x,405,"Usa GET");return;}
   String id=parameters(x.getRequestURI().getRawQuery()).getOrDefault("id","");if(!id.matches("[a-f0-9]{32}")){error(x,400,"Trabajo inválido");return;}
   Path status=root.resolve("altas/trabajos").resolve(id).resolve("estado.json");if(!Files.exists(status)){error(x,404,"Trabajo no encontrado");return;}var data=CorpusMultidocumento.read(status);if(data.get("estado").getAsString().equals("PROCESANDO")){Path pidFile=status.getParent().resolve("pid.txt");if(Files.exists(pidFile)){long pid=Long.parseLong(Files.readString(pidFile).strip());if(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)==false){data.addProperty("estado","ERROR");data.addProperty("detalle","El proceso se interrumpió. Comprueba el catálogo y los pendientes antes de reintentar");}}}json(x,200,data);return;
  }
  if(!x.getRequestMethod().equals("GET")){error(x,405,"Usa GET");return;}
  if(path.equals("/api/inicio")){refresh();var j=new JsonObject();j.addProperty("token",token);j.addProperty("indice",activeIndex.getParent().getFileName().toString());j.addProperty("fragmentos",fragmentCount);j.add("fuentes",catalog);j.addProperty("carpeta_entrada",root.resolve("entrada-circulares").toString());j.addProperty("pendientes",IncorporarCircular.pending(root).size());json(x,200,j);return;}
  if(path.startsWith("/pdf/")){Path p=pdfs.get(path.substring(5));if(p==null){error(x,404,"PDF no encontrado");return;}send(x,200,"application/pdf",Files.readAllBytes(p));return;}
  String name=switch(path){case "/"->"index.html";case "/app.js"->"app.js";case "/style.css"->"style.css";default->null;};
  if(name==null){error(x,404,"No encontrado");return;}
  send(x,200,name.endsWith("js")?"text/javascript; charset=utf-8":name.endsWith("css")?"text/css; charset=utf-8":"text/html; charset=utf-8",Files.readAllBytes(root.resolve("interfaz").resolve(name)));
 }catch(Exception e){e.printStackTrace();try{error(x,500,"Error local. Revisa la consola del servidor.");}catch(Exception ignored){x.close();}}}
 static Map<String,String> parameters(String query){Map<String,String> result=new HashMap<>();if(query!=null)for(String pair:query.split("&")){String[] parts=pair.split("=",2);result.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),parts.length>1?URLDecoder.decode(parts[1],StandardCharsets.UTF_8):"");}return result;}
 static boolean safeName(String name){return name.length()>4&&name.length()<=150&&name.toLowerCase(Locale.ROOT).endsWith(".pdf")&&!name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")&&!name.startsWith(".")&&!name.matches("(?i)(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])\\..*");}
 JsonObject launch(String filename,String key)throws Exception{
  String id=UUID.randomUUID().toString().replace("-","");Path dir=root.resolve("altas/trabajos").resolve(id);Files.createDirectories(dir);Path status=dir.resolve("estado.json");var initial=new JsonObject();initial.addProperty("estado","PROCESANDO");initial.addProperty("detalle","Preparando incorporación local");RegistroAcervo.atomic(status,initial);
  var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Xmx512m","-Djava.awt.headless=true","-cp",System.getProperty("java.class.path"),"mx.edu.piloto.IncorporarCircular",root.toString(),status.toString(),filename,key).redirectErrorStream(true).redirectOutput(dir.resolve("proceso.log").toFile()).start();
  Files.writeString(dir.resolve("pid.txt"),Long.toString(process.pid()));
  Thread.ofPlatform().daemon().start(()->{try{
   if(!process.waitFor(60,TimeUnit.MINUTES)){process.destroyForcibly().waitFor();throw new IllegalStateException("Tiempo máximo de procesamiento superado");}
   if(process.exitValue()!=0)throw new IllegalStateException("No se completó la incorporación. Revisa el registro local");refresh();
  }catch(Exception e){try{var failure=new JsonObject();failure.addProperty("estado","ERROR");failure.addProperty("detalle",e.getMessage());RegistroAcervo.atomic(status,failure);}catch(Exception ignored){}}finally{busy.release();}});
  var response=new JsonObject();response.addProperty("trabajo",id);response.addProperty("estado","PROCESANDO");return response;
 }
 public static void main(String[] args)throws Exception{
  int port=args.length>1?Integer.parseInt(args[1]):8765;if(port<1024||port>65535)throw new IllegalArgumentException("Puerto inválido");
  var app=new InterfazLocal(Path.of(args[0]));HttpServer server;try{server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),8);}catch(java.net.BindException e){System.out.println("El puerto "+port+" ya está en uso. Abre http://127.0.0.1:"+port+"/ si la interfaz ya está activa, o inicia con otro puerto.");return;}server.createContext("/",app::handle);server.setExecutor(Executors.newFixedThreadPool(4));Runtime.getRuntime().addShutdownHook(new Thread(()->server.stop(0)));server.start();System.out.println("Abre http://127.0.0.1:"+port+"/ — Ctrl+C para detener. Una consulta a la vez.");
 }
}
