'use strict';
let sesion, fuentes=[], ocupado=false, trabajo=null;
const memoriaTrabajo='circulares-trabajo';
function controles(value){ocupado=value;for(const id of ['enviar','subir','procesar'])$(id).disabled=value||!sesion;}
const $=id=>document.getElementById(id);
const texto=(tag,value,cls)=>{const e=document.createElement(tag);e.textContent=value;if(cls)e.className=cls;return e;};
const estados={YA_REPRESENTADA_INDICE_06:'Representada en el índice anterior; conserva su revisión y exclusiones',INDEXABLE_AUTOMATICO_NO_REVISADO:'Transcripción automática no revisada',EXCLUSION_PREVIA_CONSERVADA:'Excluida de la consulta: decisión previa conservada'};
function enlace(url,label){const a=texto('a',label);a.href=url;a.target='_blank';a.rel='noopener';return a;}
function catalogar(){
 const q=$('filtro').value.toLocaleLowerCase('es').trim();const selected=fuentes.filter(f=>[f.archivo,f.clave_propuesta,...(f.claves_indice||[]),...f.archivos_equivalentes].join(' ').toLocaleLowerCase('es').includes(q));
 $('conteo').textContent=selected.length+(selected.length===1?' PDF único':' PDF únicos');$('catalogo').replaceChildren();
 for(const f of selected){const d=document.createElement('details');d.append(texto('summary',f.archivo+' · '+(f.claves_indice?.join(' / ')||f.clave_propuesta||'Sin clave identificada')+' · '+f.paginas_total+' pág.'));d.append(enlace('/pdf/'+f.fuente_id,'Abrir PDF'));const preguntar=texto('button','Preguntar sobre este archivo','secundario');preguntar.type='button';preguntar.addEventListener('click',()=>{$('pregunta').value='En el archivo "'+f.archivo+'", ';$('pregunta').focus();});d.append(preguntar);
 if(f.archivos_equivalentes.length>1)d.append(texto('p','Archivos idénticos: '+f.archivos_equivalentes.join(', '),'ayuda'));
 for(const p of f.paginas)d.append(texto('div','Página '+p.pagina+': '+(estados[p.estado]||p.estado.replaceAll('_',' ').toLowerCase()),'pagina'));$('catalogo').append(d);}
}
$('filtro').addEventListener('input',catalogar);
$('consulta').addEventListener('submit',async event=>{
 event.preventDefault();const pregunta=$('pregunta').value.trim();if(!pregunta)return;
 controles(true);$('estado').className='';$('estado').textContent='Consultando el modelo local… Puede tardar varios minutos.';$('resultado').hidden=true;
 try{const response=await fetch('/api/consulta',{method:'POST',headers:{'Content-Type':'application/json','X-Piloto-Token':sesion},body:JSON.stringify({pregunta})});const r=await response.json();if(!response.ok)throw new Error(r.error||'No se completó la consulta');
 $('tipo').textContent=r.estado.replaceAll('_',' ');$('respuesta').textContent=r.respuesta;$('citas').replaceChildren();
 for(const c of r.citas||[]){const card=texto('div','','fuente');card.append(texto('strong',c.clave_cita||c.clave||c.documento_id));card.append(texto('p','Páginas: '+(c.paginas||[c.pagina]).join(', ')+' · '+(c.nivel_revision||'Ver revisión en la respuesta')));if(c.identidad_en_conflicto)card.append(texto('p','Clave repetida en archivos distintos. Distingue la fuente por su PDF.','error'));if(c.pdf_url)card.append(enlace(c.pdf_url,'Abrir PDF en la página citada'));else card.append(texto('p','Consulta el PDF en el catálogo; no se pudo vincular esta cita automáticamente.','ayuda'));$('citas').append(card);}
 if(!r.citas?.length)$('citas').append(texto('p','Esta respuesta no contiene citas.'));
 $('guardado').textContent='Resultado y trazabilidad guardados en: '+r.carpeta_resultado;$('resultado').hidden=false;$('estado').textContent='Consulta terminada.';
 }catch(error){$('estado').textContent=error.message;$('estado').className='error';}finally{controles(false);}
});
async function actualizar(){const response=await fetch('/api/inicio');if(!response.ok)throw new Error('No se pudo cargar el acervo');const data=await response.json();sesion=data.token;fuentes=data.fuentes;$('resumen').textContent=fuentes.length+' PDF únicos · '+data.fragmentos+' fragmentos';$('carpeta').textContent=data.carpeta_entrada;$('pendientes').textContent=data.pendientes+' PDF pendientes. Los incorporados y duplicados se trasladan a la subcarpeta procesados; los que fallen permanecen para revisión.';catalogar();controles(ocupado);}
$('actualizar').addEventListener('click',()=>actualizar().catch(e=>{$('estado-alta').textContent=e.message;}));
function resultadoAlta(r){const card=texto('div','','fuente');card.append(texto('strong',r.archivo+' · '+r.estado.replaceAll('_',' ')));if(r.detalle)card.append(texto('p',r.detalle));if(r.aviso)card.append(texto('p',r.aviso,'error'));if(r.estado==='DISPONIBLE_PARA_CONSULTA'){card.append(texto('p',(r.clave_propuesta||'Sin clave propuesta')+' · '+r.fragmentos+' fragmentos · Transcripción automática no revisada'));const b=texto('button','Preguntar sobre este archivo','secundario');b.addEventListener('click',()=>{$('pregunta').value='En el archivo "'+r.archivo+'", ';$('pregunta').focus();});card.append(b);}return card;}
async function vigilar(){
 try{const response=await fetch('/api/trabajo?id='+encodeURIComponent(trabajo));const r=await response.json();if(!response.ok)throw new Error(r.error||'No se pudo consultar el progreso');
  if(r.estado==='PROCESANDO'){$('estado-alta').textContent=r.detalle||'Procesando PDF…';setTimeout(vigilar,1800);return;}
  localStorage.removeItem(memoriaTrabajo);trabajo=null;$('altas-resultados').replaceChildren();
  if(r.estado==='ERROR'){$('estado-alta').textContent=r.detalle||'No se completó el procesamiento';$('estado-alta').className='error';}
  else{$('estado-alta').textContent=r.resultados?.length?'Procesamiento terminado. Consulta el resultado de cada archivo.':'No hay PDF pendientes.';for(const item of r.resultados||[])$('altas-resultados').append(resultadoAlta(item));}
  ocupado=false;await actualizar();controles(false);
 }catch(e){$('estado-alta').textContent='No se pudo leer el progreso. Se intentará de nuevo; no vuelvas a subir el PDF. '+e.message;setTimeout(vigilar,5000);}
}
async function incorporar(url,body){
 controles(true);$('estado-alta').className='';$('estado-alta').textContent='Preparando incorporación…';$('altas-resultados').replaceChildren();
 try{const response=await fetch(url,{method:'POST',headers:{'X-Piloto-Token':sesion,'Content-Type':'application/pdf'},body});const r=await response.json();if(!response.ok)throw new Error(r.error||'No se pudo iniciar');trabajo=r.trabajo;localStorage.setItem(memoriaTrabajo,trabajo);vigilar();}
 catch(e){$('estado-alta').textContent=e.message;$('estado-alta').className='error';controles(false);}
}
$('alta').addEventListener('submit',event=>{event.preventDefault();const file=$('pdf').files[0];if(!file)return;if(file.size>30*1024*1024){$('estado-alta').textContent='El PDF supera 30 MB';return;}incorporar('/api/subir?nombre='+encodeURIComponent(file.name)+'&clave='+encodeURIComponent($('clave').value.trim()),file);});
$('procesar').addEventListener('click',()=>incorporar('/api/procesar',null));
(async()=>{try{await actualizar();trabajo=localStorage.getItem(memoriaTrabajo);if(trabajo){controles(true);vigilar();}else controles(false);}catch(e){$('resumen').textContent=e.message;}})();
