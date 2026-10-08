#!/usr/bin/env python3
"""Local embedding comparison; no production writes, dependencies, or generated answers."""
import argparse
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import statistics
import subprocess
import time
from urllib.request import Request, urlopen


# Hand-labelled smoke suite, not an external benchmark. Each pair has one target document.
CASES = [
    ('train', 'travel', 'การเดินทางด้วยรถไฟ', 'ผู้ใช้ชอบนั่งรถไฟชมวิวและเลือกตู้นอนเมื่อเดินทางไกล', 'ชอบเดินทางไกลด้วยพาหนะอะไรที่มีตู้นอน', 'Which transport does the user prefer for scenic overnight trips?'),
    ('car', 'travel', 'การเดินทางด้วยรถยนต์', 'ผู้ใช้ขับรถส่วนตัวไปทำงาน เพราะต้องแวะรับลูกระหว่างทาง', 'ไปทำงานอย่างไรถ้าต้องรับลูกด้วย', 'How does the user commute when picking up their child?'),
    ('bus', 'travel', 'การเดินทางด้วยรถโดยสาร', 'ผู้ใช้ขึ้นรถเมล์ไปตลาดเพราะประหยัดค่าเดินทางและไม่ต้องหาที่จอด', 'ไปตลาดแบบประหยัดและไม่ต้องจอดรถ', 'Which transport is used for cheap market trips without parking?'),
    ('coffee', 'food', 'กาแฟที่ชอบ', 'ผู้ใช้ชอบอเมริกาโนเย็นไม่ใส่น้ำตาล และดื่มเฉพาะตอนเช้า', 'กาแฟตอนเช้าควรสั่งหวานไหม', 'What coffee does the user drink in the morning?'),
    ('sleep', 'food', 'เครื่องดื่มก่อนนอน', 'ก่อนนอนผู้ใช้ดื่มชาคาโมมายล์และหลีกเลี่ยงกาแฟเพราะคาเฟอีนทำให้นอนไม่หลับ', 'ก่อนนอนควรเลือกชาชนิดไหนเพื่อเลี่ยงคาเฟอีน', 'Which bedtime drink avoids caffeine and sleep problems?'),
    ('peanut', 'food', 'แพ้ถั่วลิสง', 'ผู้ใช้แพ้ถั่วลิสง ต้องตรวจซอสและขนมว่าไม่มีถั่วลิสงก่อนรับประทาน', 'ต้องตรวจส่วนผสมถั่วอะไรในซอสก่อนกิน', 'Which nut allergy requires checking sauces and snacks?'),
    ('shrimp', 'food', 'แพ้กุ้ง', 'เพื่อนของผู้ใช้แพ้กุ้ง ต้องหลีกเลี่ยงกะปิ น้ำพริกกุ้ง และอาหารทะเลที่ปนเปื้อนกุ้ง', 'ทำอาหารให้เพื่อนต้องระวังกะปิเพราะอะไร', 'Why must shrimp paste be avoided when cooking for the friend?'),
    ('bangkok', 'weather', 'อากาศกรุงเทพ', 'กรุงเทพช่วงบ่ายมีฝนฟ้าคะนอง ควรพกร่มและเผื่อเวลาเดินทาง', 'ออกไปข้างนอกช่วงบ่ายในกรุงเทพควรเตรียมอะไร', 'What should I carry for an afternoon outing in Bangkok?'),
    ('chiangmai', 'weather', 'อากาศเชียงใหม่', 'เชียงใหม่มีฝุ่น PM2.5 สูง ควรใส่หน้ากากและงดออกกำลังกายกลางแจ้ง', 'เชียงใหม่ฝุ่นสูงควรปรับการวิ่งอย่างไร', 'What exercise precautions apply during Chiang Mai air pollution?'),
    ('backup', 'homelab', 'สำรองข้อมูล PostgreSQL', 'สำรองฐานข้อมูล PostgreSQL ด้วย pg_dump รูปแบบ custom แล้วเก็บไฟล์สำรองแยกจากเครื่องหลัก', 'จะสร้างไฟล์สำรองฐานข้อมูล postgres ใช้คำสั่งอะไร', 'How do I create a custom-format PostgreSQL backup?'),
    ('restore', 'homelab', 'กู้คืนข้อมูล PostgreSQL', 'กู้คืนไฟล์สำรอง PostgreSQL รูปแบบ custom ด้วย pg_restore ลงฐานข้อมูลทดสอบก่อนใช้งานจริง', 'มีไฟล์สำรองแล้วจะนำข้อมูลกลับเข้าฐานข้อมูลด้วยอะไร', 'How should an existing custom database backup be restored?'),
    ('ups', 'homelab', 'ปิดเครื่องเมื่อไฟดับ', 'NUT อ่านสถานะ UPS และสั่ง shutdown เมื่อแบตเตอรี่ต่ำ เพื่อให้ระบบหยุดอย่างปลอดภัย', 'ไฟบ้านดับแล้วแบตสำรองใกล้หมดระบบไหนควรสั่งปิดเครื่อง', 'What triggers a safe shutdown when UPS battery runs low?'),
    ('postgres', 'storage', 'ฐานข้อมูลส่วนกลาง', 'มินิคุงเก็บความจำและประวัติสนทนาใน PostgreSQL ผ่าน JDBC เพื่อให้ข้อมูลอยู่ข้ามการรีสตาร์ต', 'รีสตาร์ตแล้วความจำยังอยู่เพราะเก็บไว้ที่ไหน', 'Where is Minikun conversation history persisted across restarts?'),
    ('sqlite', 'storage', 'ฐานข้อมูลแอปออฟไลน์', 'แอปจดโน้ตออฟไลน์ใช้ SQLite เป็นไฟล์ฐานข้อมูลในเครื่อง ไม่ต้องเปิดบริการฐานข้อมูลส่วนกลาง', 'แอปโน้ตที่ไม่มีเซิร์ฟเวอร์ควรเก็บข้อมูลด้วยอะไร', 'Which database supports offline notes without a database server?'),
    ('vector', 'storage', 'ค้นความจำตามความหมาย', 'pgvector ใช้ cosine distance ค้นเวกเตอร์ embedding ที่มีความหมายใกล้กับคำถามแม้ใช้คำต่างกัน', 'ค้นบันทึกที่ใช้คำไม่เหมือนคำถามแต่ความหมายตรงกันทำอย่างไร', 'How can memories be found when their wording differs from the query?'),
    ('meeting', 'reminder', 'เตือนประชุม', 'ตั้งการแจ้งเตือนก่อนประชุมสิบห้านาที พร้อมชื่อประชุมและลิงก์ห้องวิดีโอ', 'ก่อนเข้าประชุมควรเตือนล่วงหน้ากี่นาทีและแนบอะไร', 'What lead time and link should a meeting reminder include?'),
    ('medicine', 'reminder', 'เตือนรับประทานยา', 'ตั้งเตือนกินยาหลังอาหารเย็นทุกวันเวลา 19:00 และบันทึกว่ากินแล้วหรือยัง', 'เตือนกินยาประจำวันหลังมื้อไหนและเวลาใด', 'When is the daily after-dinner medication reminder scheduled?'),
    ('timezone', 'reminder', 'เขตเวลาการแจ้งเตือน', 'เวลานัดหมายของผู้ใช้ต้องแสดงตาม Asia/Bangkok ซึ่งเป็น UTC+7 ไม่ใช่เวลาของเครื่องเซิร์ฟเวอร์', 'เวลานัดของผู้ใช้ต้องอิงโซนเวลาไหน', 'Which timezone should user appointments display in?'),
    ('owner', 'code', 'SQL ป้องกันข้อมูลข้ามเจ้าของ', 'ทุกคำสั่งค้นความจำต้องมี WHERE owner_id = ? ก่อนจัดอันดับและ LIMIT เพื่อไม่ให้ข้อมูลของผู้ใช้อื่นหลุดมา', 'จะกันความจำของคนอื่นไม่ให้ปนในผลค้นด้วยเงื่อนไข SQL อะไร', 'Which SQL predicate prevents cross-user memory retrieval?'),
    ('unique', 'code', 'ป้องกันบันทึกซ้ำ', 'ใช้ UNIQUE constraint บน fingerprint และ INSERT ON CONFLICT DO NOTHING เพื่อป้องกันบันทึกซ้ำเมื่อมีคำขอพร้อมกัน', 'สองคำขอเขียนข้อมูลเดียวกันพร้อมกันป้องกันแถวซ้ำอย่างไร', 'How do concurrent inserts avoid duplicate memory rows?'),
    ('css', 'code', 'หน้าจอมือถือ', 'ใช้ CSS media query ปรับ grid ให้เหลือคอลัมน์เดียวบนมือถือ และกำหนด min-width: 0 เพื่อไม่ให้เนื้อหาล้น', 'หน้า grid บนมือถือมีข้อความล้นควรแก้ CSS จุดไหน', 'How can a mobile grid avoid horizontal overflow?'),
    ('pairing', 'code', 'จับคู่อุปกรณ์', 'อุปกรณ์ใหม่ต้องใช้รหัสจับคู่ที่หมดอายุได้ และตรวจโทเคนก่อนเข้าถึงประวัติแชต', 'มือถือเครื่องใหม่ต้องยืนยันอะไรถึงจะอ่านแชตได้', 'How is a new device authorized to access chat history?'),
    ('summary', 'retrieval', 'สรุปบทสนทนายาว', 'เมื่อบทสนทนายาวเกินงบ context ให้สรุปข้อความเก่าและคงข้อความล่าสุดเพื่อรักษาความต่อเนื่อง', 'คุยยาวจน context ไม่พอควรเก็บข้อความแบบไหน', 'How should long conversations fit within the context budget?'),
    ('fallback', 'retrieval', 'ค้นด้วยคำเมื่อ embedding ล่ม', 'เมื่อบริการ embedding ใช้งานไม่ได้ ระบบถอยไปค้นคำตรงตัว เพื่อให้ยังเรียกคืนข้อมูลได้', 'ถ้าสร้างเวกเตอร์ไม่ได้ยังค้นข้อมูลต่อด้วยวิธีอะไร', 'What retrieval fallback is used during an embedding service outage?'),
]


def cosine(left, right):
    if not left or len(left) != len(right) or not all(math.isfinite(x) for x in left + right):
        raise ValueError('invalid embedding dimensions or non-finite values')
    norm = math.sqrt(sum(x*x for x in left) * sum(x*x for x in right))
    if not norm:
        raise ValueError('zero embedding')
    return sum(x*y for x, y in zip(left, right)) / norm


def metrics(rows):
    ranks = [r['rank'] for r in rows]
    times = sorted(t for r in rows for t in r['elapsed_ms'])
    return {'queries': len(rows), 'hit_at_1': sum(r == 1 for r in ranks) / len(rows),
            'hit_at_5': sum(r <= 5 for r in ranks) / len(rows),
            'mrr': statistics.mean(1/r for r in ranks),
            'p50_ms': statistics.median(times), 'p95_ms': times[math.ceil(.95*len(times))-1]}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--url', default='http://127.0.0.1:11436')
    p.add_argument('--output', type=Path, default=Path('target/embedding-benchmark/results.json'))
    p.add_argument('--workload', type=Path, help='Private JSON array of title/text records; never copied to report')
    p.add_argument('--repeats', type=int, default=3)
    p.add_argument('--self-test', action='store_true')
    args = p.parse_args()
    if args.self_test:
        assert cosine([1, 0], [1, 0]) == 1 and cosine([1, 0], [0, 1]) == 0
        for a, b in [([], []), ([0], [0]), ([1], [1, 0]), ([math.nan], [1])]:
            try: cosine(a, b)
            except ValueError: pass
            else: raise AssertionError('invalid vectors accepted')
        assert metrics([{'rank': 1, 'elapsed_ms': [10]}, {'rank': 2, 'elapsed_ms': [20]}])['mrr'] == .75
        assert len({c[0] for c in CASES}) == len(CASES)
        print('self-test passed'); return
    if args.repeats < 1:
        p.error('--repeats must be positive')

    def call(path, payload=None):
        req = Request(args.url.rstrip('/')+path,
                      data=None if payload is None else json.dumps(payload).encode(),
                      headers={'Content-Type': 'application/json'})
        with urlopen(req, timeout=180) as response:
            return json.load(response)

    def embed(model, texts, keep_alive='3m'):
        start = time.perf_counter()
        data = call('/api/embed', {'model': model, 'input': texts, 'truncate': False,
                                  'keep_alive': keep_alive, 'options': {'num_ctx': 8192}})
        elapsed = (time.perf_counter()-start)*1000
        vectors = data['embeddings']
        if len(vectors) != len(texts):
            raise ValueError('embedding batch size mismatch')
        for vector in vectors:
            cosine(vector, vector)
        return vectors, elapsed, data

    workload = json.loads(args.workload.read_text()) if args.workload else []
    chip, memory = subprocess.check_output(['sysctl', '-n', 'machdep.cpu.brand_string', 'hw.memsize'], text=True).splitlines()
    models = ['qwen3-embedding:0.6b', 'embeddinggemma-2:270m', 'embeddinggemma-2:270m-mxfp8-text']
    tags = {m['name']: m for m in call('/api/tags')['models']}
    report = {'created_at': datetime.now(timezone.utc).isoformat(), 'runtime': call('/api/version'),
              'hardware': {'chip': chip, 'unified_memory_gib': int(memory)/2**30},
              'suite': {'documents': len(CASES), 'queries': len(CASES)*2,
                        'repeats': args.repeats, 'workload_documents': len(workload)},
              'note': 'Hand-labelled synthetic cosine retrieval, not full Minikun RAG. One relevant document per query. '
                      'Thai paraphrase and English-to-Thai cross-language queries. No reranker or lexical blend. '
                      'Shared machine; timings include HTTP overhead. Model-reported allocation is not peak process RAM. '
                      'Private workload used only for indexing timing; its text is not saved in this report.',
              'models': []}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    for model in models:
        result = {'model': model, 'manifest_digest': tags.get(model, {}).get('digest'),
                  'download_bytes': tags.get(model, {}).get('size')}
        report['models'].append(result)
        try:
            call('/api/embed', {'model': model, 'input': [], 'keep_alive': 0})
            _, cold, data = embed(model, ['ทดสอบการค้นคืนข้อมูล'])
            result.update(first_request_ms=cold, load_ms=data.get('load_duration', 0)/1e6,
                          metadata=call('/api/show', {'model': model}).get('details'))
            result['configurations'] = []
            # Raw-query mode approximates current memory formatting, not its SQL/hybrid pipeline.
            for mode in ['raw-query', 'recommended']:
                gemma = model.startswith('embeddinggemma') and mode == 'recommended'
                def document(title, text):
                    return f'title: {title} | text: {text}' if gemma else f'{title}: {text}'
                docs = [document(c[2], c[3]) for c in CASES]
                vectors, ms, _ = embed(model, docs)
                rows = []
                for i, case in enumerate(CASES):
                    for kind, query in [('thai', case[4]), ('cross-language', case[5])]:
                        text = query
                        if mode == 'recommended':
                            text = ('task: search result | query: '+query if gemma else
                                    'Instruct: Retrieve relevant passages that answer the query.\nQuery: '+query)
                        times = []
                        for repeat in range(args.repeats):
                            query_vectors, elapsed, _ = embed(model, [text])
                            times.append(elapsed)
                            if repeat == 0:
                                scores = [cosine(query_vectors[0], v) for v in vectors]
                                ranked = sorted(range(len(scores)), key=lambda n: (-scores[n], n))
                                row = {'id': case[0]+'/'+kind, 'category': case[1], 'kind': kind,
                                       'rank': ranked.index(i)+1, 'top_id': CASES[ranked[0]][0],
                                       'target_cosine': scores[i], 'top_cosine': scores[ranked[0]],
                                       'query': query}
                        row['elapsed_ms'] = times
                        rows.append(row)
                config = {'mode': mode, 'dimension': len(vectors[0]), 'index_ms': ms,
                          'summary': metrics(rows),
                          'by_kind': {k: metrics([r for r in rows if r['kind'] == k])
                                      for k in ['thai', 'cross-language']}, 'results': rows}
                result['configurations'].append(config)
                print(model, mode, json.dumps(config['summary']), flush=True)
            negatives = []
            for query in ['ดาวพฤหัสบดีมีดวงจันทร์กี่ดวง', 'ทำขนมปังซาวร์โดว์ให้ขึ้นฟูอย่างไร',
                          'ปลูกกล้วยไม้ให้ออกดอกต้องดูแลอย่างไร', 'กติกาการล้ำหน้าในฟุตบอลคืออะไร',
                          'ตั้งสายกีตาร์หกสายมาตรฐานอย่างไร', 'หมากรุกเดินม้าเป็นรูปแบบไหน',
                          'วาฬสีน้ำเงินกินอาหารอะไร', 'การปะทุของภูเขาไฟเกิดจากอะไร']:
                text = ('task: search result | query: '+query if gemma else
                        'Instruct: Retrieve relevant passages that answer the query.\nQuery: '+query)
                v, _, _ = embed(model, [text])
                best = max(cosine(v[0], doc) for doc in vectors)
                # Zero lexical evidence: existing personal-knowledge weight=.85, threshold=.65.
                score = .85*(best+1)/2
                negatives.append({'query': query, 'max_cosine': best, 'blended_score': score,
                                  'would_pass_personal_threshold': score >= .65})
            result['negative_probes'] = negatives
            if workload:
                start = time.perf_counter()
                for offset in range(0, len(workload), 16):
                    batch = [document(c['title'], c['text']) for c in workload[offset:offset+16]]
                    embed(model, batch)
                result['workload_index_ms'] = (time.perf_counter()-start)*1000
                print(model, 'real workload indexing ms', round(result['workload_index_ms']), flush=True)
            loaded = call('/api/ps')['models']
            result['loaded_models'] = [{k: m.get(k) for k in ['name', 'size', 'size_vram', 'context_length']} for m in loaded]
        except Exception as error:
            result['error'] = f'{type(error).__name__}: {error}'
            print(model, result['error'], flush=True)
        finally:
            args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
            try: call('/api/embed', {'model': model, 'input': [], 'keep_alive': 0})
            except Exception: pass
    print('report:', args.output)


if __name__ == '__main__':
    main()
