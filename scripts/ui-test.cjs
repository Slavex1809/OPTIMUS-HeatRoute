// DOM contract tests with a minimal harness; no visual browser claims.
const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
class Element {
 constructor(tag='div'){this.tag=tag;this.children=[];this.attrs={};this.textContent='';this.hidden=false;this.disabled=false;this.clientWidth=1000;this.clientHeight=600;this.className='';this.classList={toggle:()=>{},add:()=>{},remove:()=>{}};}
 append(...xs){this.children.push(...xs)}replaceChildren(...xs){this.children=xs}setAttribute(k,v){this.attrs[k]=v}getAttribute(k){return this.attrs[k]}addEventListener(k,v){this['on'+k]=v}querySelector(){return new Element()}showModal(){this.open=true}close(){this.open=false}
}
const elements=new Map(),element=id=>{if(!elements.has(id))elements.set(id,new Element());return elements.get(id)};
const source={type:'FeatureCollection',features:[{type:'Feature',properties:{id:1,object_type:'oks_connection_point',flow_tph:20},geometry:{type:'Point',coordinates:[37.6,55.7]}}]};
const report={targets:1,connected:1,elapsedMillis:42,targetDiagnostics:[{targetId:1,status:'CONNECTED'}]};
const summary={type:'Feature',geometry:null,properties:{id:'summary',object_type:'variant_summary',variant_id:'v1',rank:1,calculated_cost:1000,construction_cost:1000,chamber_construction_cost:0,existing_chamber_tie_in_cost:0,unconnected_penalty:0,new_network_length:1,score:.03,unconnected_oks_ids:[]}};
let requests=[];
const context=vm.createContext({console,Number,JSON,Math,Array,Object,Set,Map,Error,FormData:class{append(){}},ResizeObserver:class{observe(){}},document:{getElementById:element,createElement:tag=>new Element(tag),createElementNS:(_,tag)=>new Element(tag),querySelectorAll:()=>[]},fetch:async url=>{requests.push(url);return {ok:true,headers:{get:()=>100},json:async()=>url==='/api/v1/capabilities'?{entryPolicy:'cost'}:url.endsWith('/result')?{type:'FeatureCollection',features:[summary]}:url==='/api/v1/calculations'?{id:'job',audit:{valid:true,featureCount:1,objectTypes:{oks_connection_point:1},warnings:[]}}:{status:'COMPLETED',report}}},setTimeout:()=>{throw Error('unexpected polling')},File:class{}});
vm.runInContext(fs.readFileSync(path.join(__dirname,'../backend/src/main/resources/static/app.js'),'utf8'),context);
(async()=>{
 await context.load({name:'fixture.geojson',size:100,text:async()=>JSON.stringify(source)});
 assert.equal(requests.filter(x=>x==='/api/v1/calculations').length,1,'one upload only');assert.equal(element('entryPolicy').textContent,'По стоимости · прямой ввод');assert.equal(element('run').disabled,false);assert(element('dataInfo').textContent.includes('1'));
 await context.poll('job');assert.equal(element('report').disabled,false);assert.equal(element('variants').children.length,1);assert.equal(element('download').attrs['aria-disabled'],'false');
 context.inspect({id:'<img src=x onerror=evil()>',object_type:'heat_network'});assert(element('inspector').children[0].children[1].textContent.includes('<img'));
 context.inspect({id:1,object_type:'oks_connection_point'});assert(element('inspector').children.some(x=>x.children[1].textContent==='Подключено'));
 await context.load({name:'large.geojson',size:30*1024*1024,text:async()=>{throw Error('large file must not be parsed in browser')}});assert.equal(element('features').children.length,0);
 element('report').onclick();assert.equal(element('dialog').open,true);
 console.log('PASS: single upload, audit, ready state, result cards, download, text escaping, target diagnostics, large-file map reset, dialog. DOM harness only; visual browser QA pending.');
})().catch(e=>{console.error(e);process.exit(1)});
