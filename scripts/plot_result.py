#!/usr/bin/env python3
"""Export the calculated network as a publication-sized PNG (matplotlib)."""
import argparse,json,re,pathlib
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.path import Path
from matplotlib.patches import PathPatch
from matplotlib.lines import Line2D
from map_geometry import utm,bounds
p=argparse.ArgumentParser();p.add_argument('input');p.add_argument('result');p.add_argument('report');p.add_argument('output');a=p.parse_args()
source=json.load(open(a.input));result=json.load(open(a.result));report=json.load(open(a.report));report=report.get('report',report)
summary=next(f['properties'] for f in result['features'] if f['properties']['object_type']=='variant_summary' and f['properties']['rank']==1)
missing={str(x) for x in summary['unconnected_oks_ids']}
connected=sum(f['properties']['object_type']=='oks_connection_point' for f in source['features'])-len(missing)
status_text=f'Подключено {connected} из {connected+len(missing)}. Не подключены: '+', '.join(sorted(missing,key=int))

new=[f for f in result['features'] if f['geometry'] and f['properties']['variant_id']==summary['variant_id']];byid={str(f['properties']['id']):f for f in source['features']};targets=[f for f in source['features'] if f['properties']['object_type']=='oks_connection_point']
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':10})
fig=plt.figure(figsize=(16,11),facecolor='#f1f5f1');gs=fig.add_gridspec(3,2,width_ratios=[2.5,1],left=.025,right=.975,bottom=.10,top=.79,hspace=.27,wspace=.055)
def polygon(ax,poly,color):
 points=[];codes=[]
 for j,ring in enumerate(poly):
  r=[utm(c) for c in ring];area=sum(x*y2-x2*y for (x,y),(x2,y2) in zip(r,r[1:]))
  if (j==0 and area<0) or (j>0 and area>0):r=r[::-1]
  points.extend(r);codes.extend([Path.MOVETO]+[Path.LINETO]*(len(r)-2)+[Path.CLOSEPOLY])
 ax.add_patch(PathPatch(Path(points,codes),facecolor=color,edgecolor='#b4c4b8',linewidth=.45,zorder=1))
def render(ax,box,focus=None):
 ax.set_facecolor('#fbfcf9')
 for f in source['features']:
  q=f['properties'];g=f['geometry'];t=g['type'];c=g['coordinates'];rt=q.get('restriction_type','')
  if t in ('Polygon','MultiPolygon'):
   color='#dce5df' if rt=='oks' else '#f0e6d3' if rt in ('road','tram_tracks') else '#e9dfe4'
   for poly in [c] if t=='Polygon' else c:polygon(ax,poly,color)
  elif t=='LineString':
   xy=[utm(v) for v in c];x,y=zip(*xy);ax.plot(x,y,color='#77988b' if q['object_type']=='heat_network' else '#a8909d',lw=1.1,zorder=2)
 for f in new:
  q=f['properties'];g=f['geometry']
  if g['type']=='LineString':
   x,y=zip(*[utm(c) for c in g['coordinates']]);special=q['laying_method']=='special';ax.plot(x,y,color='#b04e23' if special else '#ee753f',lw=2,ls='--' if special else '-',zorder=3)
  elif q['object_type']=='heat_chamber':
   x,y=utm(g['coordinates']);ax.scatter([x],[y],s=22,marker='s',color='#254d3b',edgecolors='white',lw=.6,zorder=4)
 for f in targets:
  x,y=utm(f['geometry']['coordinates'])
  if not(box[0]<=x<=box[0]+box[2] and box[1]<=y<=box[1]+box[3]):continue
  ax.scatter([x],[y],s=38,facecolor=('#ca3d45' if str(f['properties']['id']) in missing else '#12805c'),edgecolors='white',lw=.9,zorder=6)
  ax.annotate(str(f['properties']['id']),(x,y),xytext=(5,5),textcoords='offset points',color='#123d2b',weight='bold',fontsize=10,zorder=7,clip_on=True)
 if focus:
  d=next(d for d in report['targetDiagnostics'] if str(d['targetId'])==focus)
  if 'selectedEntryWgs84' in d:
   nums=re.findall(r'[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?',d['selectedEntryWgs84']);x,y=utm(list(map(float,nums[:2])));ax.scatter([x],[y],s=44,facecolor='#368ccb',edgecolors='white',lw=1,zorder=8)
 ax.set_xlim(box[0],box[0]+box[2]);ax.set_ylim(box[1]+box[3],box[1]);ax.set_aspect('equal');ax.set_xticks([]);ax.set_yticks([])
 for spine in ax.spines.values():spine.set_color('#d3dfd6')
main=fig.add_subplot(gs[:,0]);box=bounds(new+targets,45);render(main,box)
main.set_title('Схема новой сети',loc='left',fontsize=14,pad=12,weight='bold',color='#163d2d')
x=box[0]+box[2]*.07;y=box[1]+box[3]*.93;main.plot([x,x+100],[y,y],color='#234633',lw=3);main.text(x+50,y-box[3]*.02,'100 м',ha='center',fontsize=9,color='#234633');main.annotate('С',xy=(.95,.96),xytext=(.95,.86),xycoords='axes fraction',ha='center',arrowprops={'arrowstyle':'->','color':'#234633'},color='#234633')
for i,id in enumerate(['2','5','10']):
 d=next(d for d in report['targetDiagnostics'] if str(d['targetId'])==id);ax=fig.add_subplot(gs[i,1]);render(ax,bounds([byid[str(d['ownBuildingId'])]],18),id);ax.set_title(f'Точка {id} · {"не подключена" if id in missing else "подключена"}',loc='left',fontsize=12,weight='bold',pad=8,color='#174d37');ax.text(0,-.07,('Ввод не построен' if id in missing else f'Прямой ввод: {d["internalEntryLengthM"]:.2f} м'),transform=ax.transAxes,fontsize=9,color='#577060')
fig.text(.027,.952,'OPTIMUS HeatRoute',fontsize=25,weight='bold',color='#163f30');fig.text(.027,.918,'Фактический результат расчёта · исправленный датасет · EPSG:32637',fontsize=11,color='#5b7566')
for x,label,value in [(.028,'ПОДКЛЮЧЕНО',f'{connected} / {len(targets)}'),(.25,'СТРОИТЕЛЬСТВО',f'{summary["construction_cost"]/1e6:.2f} млн ₽'),(.54,'НОВАЯ СЕТЬ',f'{summary["new_network_length"]/1000:.3f} км'),(.79,'ШТРАФ',f'{summary["unconnected_penalty"]/1e6:.2f} млн ₽')]:
 fig.text(x,.876,label,fontsize=9,color='#5c7766');fig.text(x,.847,value,fontsize=18,weight='bold',color='#173f2e')
handles=[Line2D([0],[0],color='#ee753f',lw=3,label='Новая сеть'),Line2D([0],[0],color='#77988b',lw=2,label='Существующая сеть'),Line2D([0],[0],color='#b04e23',lw=2,ls='--',label='Специальный проход'),Line2D([0],[0],marker='o',color='w',markerfacecolor='#368ccb',label='Выбранный ввод')]
fig.legend(handles=handles,loc='lower left',bbox_to_anchor=(.022,.051),ncol=4,frameon=False,fontsize=10)
fig.text(.027,.033,'Выбор допустимого ввода по стоимости (cost), без условия ближайшей границы. Геометрия исходных объектов сохранена. Глобальный минимум не доказан.',fontsize=9,color='#5c7365')
dest=pathlib.Path(a.output);dest.parent.mkdir(parents=True,exist_ok=True);fig.savefig(dest,dpi=170,facecolor=fig.get_facecolor());plt.close(fig);print(dest)
