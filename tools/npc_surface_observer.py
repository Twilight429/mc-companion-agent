"""Fetch one fresh Numen NPC frame and associate RGB predictions with native block cells.

Run with the same NUMEN_CAMERA_TOKEN as the game. --bundle is the extracted v3 export
directory containing surface_net.py, weights.pt and confidence.json. No game client is installed.
"""
import argparse
import base64
import importlib.util
import io
import json
import os
from pathlib import Path
from urllib.request import Request, urlopen
from uuid import UUID


def associate(frame, probabilities, thresholds):
    """Aggregate visual hypotheses only over depth-verified native sample indices."""
    import numpy as np

    if frame['schema'] != 'numen.surface_frame.v1':
        raise ValueError('Unsupported frame schema')
    if probabilities.shape != (9, 90, 160):
        raise ValueError('Expected the unchanged v3 output grid: 9 x 90 x 160')
    if not np.isfinite(probabilities).all():
        raise ValueError('Non-finite model probabilities')
    result = []
    seen = set()
    for source in frame['objects']:
        indices = np.asarray(source['sample_indices'], dtype=np.int64)
        if indices.size == 0 or (indices < 0).any() or (indices >= 14400).any():
            raise ValueError('Invalid surface indices')
        if len(set(indices.tolist())) != len(indices) or seen.intersection(indices.tolist()):
            raise ValueError('A sample cannot belong to multiple native objects')
        seen.update(indices.tolist())
        values = probabilities.reshape(9, -1)[:, indices]
        pred = values.argmax(axis=0)
        confidence = values.max(axis=0)
        votes = {}
        for k in (1, 2, 3):
            threshold = thresholds[k]
            if threshold is not None:
                count = int(((pred == k) & (confidence >= threshold)).sum())
                if count:
                    votes[str(k)] = count
        row = {key: source[key] for key in ('object_id', 'block_pos', 'native_block_id', 'native_block_state')}
        row.update(frame_id=frame['frame_id'], last_seen_ms=frame['captured_at_ms'],
                   verified_surface_samples=len(indices),
                   mean_visual_probabilities=values.mean(axis=1).tolist(),
                   accepted_visual_votes=votes,
                   image_coverage=len(indices) / 14400,
                   evidence='RGB hypothesis on client depth-verified native block; native facts retained')
        result.append(row)
    return {'schema': 'numen.visual_objects.v1', 'frame_id': frame['frame_id'],
            'world_session': frame['world_session'],
            'dimension': frame['dimension'], 'companion': frame['companion'],
            'grid_origin': frame['grid_origin'], 'grid_shape': frame['grid_shape'],
            'objects': result,
            'limitations': 'Block cells, not whole-tree instances. No task-value or language alignment. '
                            'No association means unknown, not empty. Dynamic/shader alignment requires live validation.'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    source = p.add_mutually_exclusive_group(required=True)
    source.add_argument('--companion', type=UUID)
    source.add_argument('--frame', type=Path, help='Replay an explicitly saved synchronized frame (not a fresh capture)')
    p.add_argument('--url', default='http://127.0.0.1:8766/npc/camera')
    p.add_argument('--bundle', type=Path)
    p.add_argument('--capture-only', action='store_true', help='Windows stdlib capture, for WSL environments without Windows loopback access')
    p.add_argument('--out', type=Path, required=True, help='New output directory')
    p.add_argument('--device', choices=('cpu', 'cuda'), default='cpu')
    args = p.parse_args()
    from urllib.parse import urlsplit
    url = urlsplit(args.url)
    if url.scheme != 'http' or url.hostname != '127.0.0.1' or url.path != '/npc/camera' or url.query:
        raise ValueError('Use the local http://127.0.0.1:<port>/npc/camera endpoint')
    if args.frame:
        raw = args.frame.read_bytes()
    else:
        token = os.environ['NUMEN_CAMERA_TOKEN']
        request = Request(f'{args.url}?companion={args.companion}',
                          headers={'Authorization': f'Bearer {token}'})
        with urlopen(request, timeout=5) as response:
            raw = response.read(8_000_001)
    if len(raw) > 8_000_000:
        raise ValueError('Oversized frame')
    frame = json.loads(raw)
    if args.companion and frame['companion'] != str(args.companion):
        raise ValueError('Companion identity mismatch')
    if args.capture_only:
        args.out.mkdir(parents=True, exist_ok=False)
        (args.out / 'frame.json').write_text(json.dumps(frame), encoding='utf-8')
        (args.out / 'rgb.png').write_bytes(base64.b64decode(frame['rgb_png_base64'], validate=True))
        print(json.dumps({'output': str(args.out), 'frame_id': frame['frame_id'], 'mode': 'capture-only'}))
        return
    if args.bundle is None:
        p.error('--bundle is required for inference')

    import numpy as np
    import torch
    from PIL import Image, ImageDraw
    spec = importlib.util.spec_from_file_location('numen_surface_net', args.bundle / 'surface_net.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    torch.set_num_threads(4)
    model = module.SurfaceFocusNet()
    model.load_state_dict(torch.load(args.bundle / 'weights.pt', map_location='cpu', weights_only=True))
    model = model.to(args.device).eval()
    image = Image.open(io.BytesIO(base64.b64decode(frame['rgb_png_base64'], validate=True))).convert('RGB')
    if image.size != (640, 360):
        raise ValueError('Wrong RGB resolution')
    rgb = np.asarray(image.resize((320, 180), Image.Resampling.BILINEAR)).copy()
    x = torch.from_numpy(rgb).permute(2, 0, 1).float().unsqueeze(0).to(args.device) / 255
    from contextlib import nullcontext
    with torch.inference_mode(), (torch.autocast('cuda', dtype=torch.float16) if args.device == 'cuda' else nullcontext()):
        prob = model(x).float().softmax(1)[0].cpu().numpy()
    calibration = json.loads((args.bundle / 'confidence.json').read_text(encoding='utf-8'))
    objects = associate(frame, prob, calibration['thresholds'])
    objects['observation_mode'] = 'saved-frame replay' if args.frame else 'fresh HTTP capture'
    args.out.mkdir(parents=True, exist_ok=False)
    (args.out / 'frame.json').write_text(json.dumps(frame), encoding='utf-8')
    (args.out / 'objects.json').write_text(json.dumps(objects, indent=2), encoding='utf-8')
    np.savez_compressed(args.out / 'predictions.npz', probabilities=prob)
    image.save(args.out / 'rgb.png')
    overlay = image.copy()
    draw = ImageDraw.Draw(overlay)
    for source in frame['objects']:
        for index in source['sample_indices']:
            u, v = index % 160, index // 160
            draw.point((4*u+2, 4*v+2), fill=(0, 255, 255))
    overlay.save(args.out / 'verified_surfaces.png')
    print(json.dumps({'output': str(args.out), 'frame_id': frame['frame_id'], 'objects': len(objects['objects'])}))


if __name__ == '__main__':
    main()
