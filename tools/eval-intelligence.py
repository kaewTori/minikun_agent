#!/usr/bin/env python3
"""Compare installed Ollama models on the Thai/temporal/reasoning suite without tools or user data."""
import argparse
import json
import math
from pathlib import Path
import statistics
import time
from urllib.request import Request, urlopen


def score(case, answer):
    text = answer.strip()
    return bool(text) and all(s in text for s in case['mustContain']) and not any(
        s in text for s in case['mustNotContain']) and (not case['maxCharacters'] or len(text) <= case['maxCharacters'])


def summary(rows):
    groups = {}
    for group in ['all'] + sorted({r['category'] for r in rows}):
        values = [r for r in rows if group == 'all' or r['category'] == group]
        times = sorted(r['elapsedMs'] for r in values if not r.get('error'))
        groups[group] = {'total': len(values), 'passed': sum(r['passed'] for r in values),
                         'errors': sum(bool(r.get('error')) for r in values),
                         'p50Ms': statistics.median(times) if times else None,
                         'p95Ms': times[math.ceil(.95 * len(times))-1] if times else None}
    return groups


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--url', default='http://127.0.0.1:11434')
    p.add_argument('--model')
    p.add_argument('--think', choices=['off', 'on', 'low', 'medium', 'high'], default='off')
    p.add_argument('--suite', type=Path, default=Path(__file__).resolve().parents[1] / 'src/main/resources/evals/intelligence.json')
    p.add_argument('--output', type=Path, default=Path('/tmp/minikun-intelligence-eval.json'))
    p.add_argument('--limit', type=int)
    p.add_argument('--timeout', type=float, default=120)
    p.add_argument('--tokens', type=int, default=2048)
    p.add_argument('--system-prompt', default='ตอบตามเจตนาผู้ใช้ รักษาขอบเขตเวลาและบุคคล อย่าสร้างข้อมูลที่ไม่มีหลักฐาน ปฏิบัติตามรูปแบบคำตอบที่ระบุ')
    p.add_argument('--self-test', action='store_true')
    args = p.parse_args()
    if args.self_test:
        case = {'mustContain':['A'],'mustNotContain':[], 'maxCharacters':1}
        assert score(case, ' A ') and not score(case,'Answer A') and not score(case,'B')
        assert summary([])['all']['p95Ms'] is None
        assert summary([{'category':'x','passed':True,'elapsedMs':10}])['all']['passed'] == 1
        print('self-test passed'); return 0
    if not args.model or args.tokens <= 0 or args.timeout <= 0 or (args.limit is not None and args.limit < 1):
        p.error('--model and positive limits are required')
    cases = json.loads(args.suite.read_text())
    if args.limit: cases = cases[:args.limit]
    results = []
    args.output.parent.mkdir(parents=True, exist_ok=True)
    for case in cases:
        messages = [{'role':'system','content':args.system_prompt}]
        start = time.monotonic(); first_answer = None; answer = ''; usage = {}
        result = {'id':case['id'], 'category':case['id'].rsplit('-',1)[0], 'passed':False}
        try:
            for turn in case['turns']:
                messages.append({'role':'user','content':turn + ('\n/no_think' if args.think == 'off' and args.model.startswith('qwen3:') else '')})
                payload = {'model':args.model,'messages':messages,'stream':True,
                           'think':{'off':False,'on':True}.get(args.think,args.think),
                           'options':{'temperature':0,'num_predict':args.tokens,'num_ctx':8192}}
                request = Request(args.url.rstrip('/')+'/api/chat',data=json.dumps(payload).encode(),headers={'Content-Type':'application/json'})
                answer=''
                with urlopen(request,timeout=args.timeout) as response:
                    for line in response:
                        chunk=json.loads(line)
                        if chunk.get('error'): raise RuntimeError(chunk['error'])
                        content=chunk.get('message',{}).get('content','')
                        if content and first_answer is None: first_answer=round((time.monotonic()-start)*1000)
                        answer += content
                        if chunk.get('done'): usage={k:chunk.get(k) for k in ('eval_count','prompt_eval_count','load_duration','eval_duration','done_reason')}
                messages.append({'role':'assistant','content':answer})
            leaked = '</think>' in answer or '<think>' in answer
            if '</think>' in answer: answer=answer.rsplit('</think>',1)[1].strip()
            elif '<think>' in answer: answer=''
            passed=score(case,answer) and not leaked
            if len(answer.strip()) > case['maxCharacters'] > 0: answer='[invalid answer format; content omitted]'
            result.update(answer=answer,passed=passed,reasoningInContent=leaked,rubric=case['rubric'],usage=usage)
        except Exception as error:
            result['error']=type(error).__name__ # no retry; generation may still be running
        result.update(elapsedMs=round((time.monotonic()-start)*1000),firstAnswerMs=first_answer)
        results.append(result)
        report={'model':args.model,'think':args.think,'tokens':args.tokens,'suite':args.suite.name,
                'summary':summary(results),'results':results,
                'note':'Model-only synthetic benchmark; not a claim of persistent memory correctness or all Thai dialect coverage. First case includes cold-load cost. Thinking traces are not stored.'}
        args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        print(f"{case['id']}: {'PASS' if result['passed'] else 'FAIL'} ({result['elapsedMs']} ms)",flush=True)
    return 0 if all(r['passed'] for r in results) else 1


if __name__=='__main__':
    raise SystemExit(main())
