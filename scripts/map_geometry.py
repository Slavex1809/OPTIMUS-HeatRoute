"""WGS84 to UTM 37N map coordinates (screen Y increases south)."""
import math
def utm(c):
 lon,lat=map(math.radians,c[:2]);e=.0066943799901413165;ep=e/(1-e);A=math.cos(lat)*(lon-math.radians(39));t=math.tan(lat)**2;C=ep*math.cos(lat)**2;N=6378137/math.sqrt(1-e*math.sin(lat)**2)
 M=6378137*((1-e/4-3*e**2/64-5*e**3/256)*lat-(3*e/8+3*e**2/32+45*e**3/1024)*math.sin(2*lat)+(15*e**2/256+45*e**3/1024)*math.sin(4*lat)-35*e**3/3072*math.sin(6*lat))
 return (500000+.9996*N*(A+(1-t+C)*A**3/6+(5-18*t+t*t+72*C-58*ep)*A**5/120),-.9996*(M+N*math.tan(lat)*(A*A/2+(5-t+9*C+4*C*C)*A**4/24+(61-58*t+t*t+600*C-330*ep)*A**6/720)))
def coords(g):
 v=g['coordinates']
 def walk(x):
  if isinstance(x[0],(int,float)):yield utm(x)
  else:
   for y in x:yield from walk(y)
 return list(walk(v))
def bounds(features,pad=20):
 c=[c for f in features if f['geometry'] for c in coords(f['geometry'])];xs,ys=zip(*c);return [min(xs)-pad,min(ys)-pad,max(xs)-min(xs)+2*pad,max(ys)-min(ys)+2*pad]
