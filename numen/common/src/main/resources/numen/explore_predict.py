"""One on-demand frozen ONNX inference. stdout is a compact direction receipt."""
import base64,io,json,sys
import numpy as np
import onnxruntime as ort
from PIL import Image

def predict(model,request):
    image=Image.open(io.BytesIO(base64.b64decode(request['png']))).convert('RGB')
    # Match the deployed model preprocessing; input dimensions come from ONNX.
    runtime=ort.InferenceSession(model,providers=['CPUExecutionProvider'])
    shape=runtime.get_inputs()[0].shape
    h,w=int(shape[2]),int(shape[3])
    rgb=np.asarray(image.resize((w,h),Image.Resampling.BILINEAR),dtype=np.float32)/255
    tensor=((rgb-np.array([.485,.456,.406],np.float32))/np.array([.229,.224,.225],np.float32)).transpose(2,0,1)[None]
    logits=runtime.run(None,{runtime.get_inputs()[0].name:tensor})[0][0]
    probabilities=np.exp(logits-logits.max(0,keepdims=True));probabilities/=probabilities.sum(0,keepdims=True)
    classes={'terrain':0,'water':1,'lava':2,'wood':3,'leaves':4,'ore':5,'constructed':6}
    goal=request['goal'];result=[]
    for sector in range(3):
        part=probabilities[:,probabilities.shape[1]//3:,sector*probabilities.shape[2]//3:(sector+1)*probabilities.shape[2]//3]
        scores=part.mean((1,2))
        score=float(scores[classes[goal]]) if goal!='any' else float(1-scores[2])
        # Vertical FOV is 70 degrees (640x360); perspective projection, image left to right.
        angle=float(np.degrees(np.arctan(((sector+.5)/3*2-1)*(640/360)*np.tan(np.radians(35)))))
        # A missing resource is not the end of a search session: visible terrain/vegetation
        # still ranks where to look next. Native queries verify actual resource coordinates.
        exploration_score=float(scores[0]+.5*scores[4]+.25*scores[3]+.25*scores[1])
        if (score>=.05 or request.get('session',False)) and float(scores[2])<.1:
            result.append(dict(yaw_offset=angle,score=score,lava_fraction=float(scores[2]),
                               exploration_score=exploration_score,
                               semantic_scores={name:float(scores[index]) for name,index in classes.items()}))
    return sorted(result,key=lambda c:-(c['score']+.25*c['exploration_score']))

if __name__=='__main__':print(json.dumps(predict(sys.argv[1],json.load(sys.stdin))))
