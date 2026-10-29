#!/usr/bin/env python3
"""Summarize ONE local synthetic load-test log, never normal browsing traffic.

Complete-response timing is not page load or Xray-route latency. HTTP callback
completion is not health success. Errors remain separate from response percentiles.
"""
import argparse
import collections
import json
import math
import re
import statistics
from pathlib import Path

HOSTS = ('www.gstatic.com', 'cp.cloudflare.com', 'example.com')
TRACE_HOSTS = HOSTS + ('speed.cloudflare.com',)
FIELDS = re.compile(r'(?<!\S)([a-z_]+)=([^\s]+)')


def number(value):
    try:
        result = int(value)
        return result if result >= 0 else None
    except (ValueError, TypeError):
        return None


def interval(fields):
    start = number(fields.get('request_start_ms'))
    end = number(fields.get('request_end_ms'))
    return (start, end) if start is not None and end is not None and end > start else None


def correlation(fields):
    run = fields.get('run_id', '')
    request = number(fields.get('request_id'))
    if re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', run) and \
       request is not None and request < 2**63 - 1:
        return run, request
    return None


def metrics(values):
    values = sorted(values)
    return {'n': len(values), 'median': statistics.median(values) if values else None,
            'p95_nearest_rank': values[math.ceil(len(values) * .95) - 1] if values else None,
            'max': values[-1] if values else None,
            'over_1000ms': sum(x > 1000 for x in values),
            'over_5000ms': sum(x > 5000 for x in values),
            'over_6000ms': sum(x > 6000 for x in values)}


def summarize(lines):
    ends, legacy, probes, traces, bodies = [], [], [], [], []
    end_ids, legacy_ids, run_ids = [], [], set()
    progress_records, trace_records, controls = {}, {}, []
    for line in lines:
        if 'LIVE_VPN ' not in line:
            continue
        fields = dict(FIELDS.findall(line.split('LIVE_VPN ', 1)[1]))
        event = fields.get('event')
        span = interval(fields)
        key = correlation(fields)
        if key:
            run_ids.add(key[0])
            if len(run_ids) > 1:
                raise ValueError('Multiple diagnostic runs: analyze each run separately')
        if event == 'transfer_progress' and key:
            progress_records[key] = number(fields.get('observed_bytes'))
        if event == 'upload_control' and fields.get('path') in ('tun', 'socks'):
            controls.append((fields['path'], fields.get('outcome'), key, number(fields.get('expected_bytes'))))
        if event == 'download_body':
            received = number(fields.get('received_bytes'))
            expected = number(fields.get('expected_bytes'))
            if received is not None and expected is not None and expected > 0:
                bodies.append((received, expected))
        if event == 'bulk_end' and fields.get('kind') in ('download', 'upload'):
            if fields.get('outcome') in ('completed', 'failed'):
                ends.append((fields['outcome'] + '_' + fields['kind'], span))
                end_ids.append(key)
        elif span and number(fields.get('download_bytes')):
            legacy.append(('completed_download', span))
            legacy_ids.append(key)
        elif span and number(fields.get('upload_bytes')):
            legacy.append(('completed_upload', span))
            legacy_ids.append(key)
        if event == 'http_timing' and fields.get('endpoint') in TRACE_HOSTS:
            # Copy only fixed diagnostic tokens; never echo arbitrary input values.
            stages = {}
            for item in fields.get('stages', '').split(','):
                name, _, value = item.partition(':')
                if name in ('dns_start', 'dns_end', 'connect_start', 'connect_end',
                            'tls_start', 'tls_end', 'request_sent', 'request_body_end',
                            'response_headers', 'response_body_end') and number(value) is not None:
                    stages[name] = number(value)
            reuse = {'true': True, 'false': False}.get(fields.get('acquired_without_connect'))
            outcome = fields.get('outcome', '')
            outcome = ('completed' if outcome == 'completed' else 'failed' if outcome.startswith('failed_')
                       else 'pending' if outcome == 'pending' else 'unknown')
            status = number(fields.get('http'))
            if key:
                trace_records[key] = number(fields.get('elapsed_ms'))
            traces.append((fields['endpoint'], stages, reuse, outcome,
                           status if status is not None and 100 <= status <= 599 else None))
        elif event is None and fields.get('phase', '').startswith('during_transfer_') and fields.get('endpoint') in HOSTS:
            probes.append((fields, span))

    # Partial capture can retain an outer success while losing its terminal
    # event. Reconcile unique same-kind containment; expose ambiguous matches.
    candidates = []
    for (legacy_kind, legacy_span), key in zip(legacy, legacy_ids):
        candidates.append([index for index, ((kind, span), terminal_key) in enumerate(zip(ends, end_ids))
                           if kind == legacy_kind and (key == terminal_key if key and terminal_key else
                              span is not None and legacy_span[0] <= span[0] and span[1] <= legacy_span[1])])
    claims = collections.Counter(index for matches in candidates for index in matches)
    uncertain = {kind for kind, span in ends if kind.startswith('completed_') and span is None}
    recovered, ambiguous = [], 0
    for record, matches in zip(legacy, candidates):
        if len(matches) == 1 and claims[matches[0]] == 1:
            continue
        if matches or record[0] in uncertain:
            ambiguous += 1
        else:
            recovered.append(record)
    transfers = ends + recovered
    result = {}
    for host in HOSTS:
        selected = [(f, span) for f, span in probes if f['endpoint'] == host]
        valid = [number(f.get('elapsed_ms')) for f, _ in selected if f.get('usable') == 'true']
        overlap = collections.Counter()
        for _, span in selected:
            if span is None:
                overlap['interval_unknown'] += 1
                continue
            matches = {kind for kind, other in transfers if other is not None and
                       other[0] <= span[0] and span[1] <= other[1]}
            overlap['inside_' + next(iter(matches)) if len(matches) == 1 else
                    'inside_multiple_kinds' if matches else 'no_known_transfer_contains_probe'] += 1
        result[host] = {
            'attempts': len(selected),
            'valid_responses': sum(f.get('usable') == 'true' for f, _ in selected),
            'invalid_responses': sum(f.get('usable') == 'false' for f, _ in selected),
            'errors': sum('error' in f for f, _ in selected),
            'unknown_results': sum('error' not in f and f.get('usable') not in ('true', 'false') for f, _ in selected),
            'valid_elapsed_ms': metrics([x for x in valid if x is not None]),
            'complete_overlap': dict(sorted(overlap.items()))}
    stage_summary = {}
    for host in TRACE_HOSTS:
        selected = [item for item in traces if item[0] == host]
        stage_summary[host] = {
            'calls': len(selected),
            'acquired_without_connect': sum(item[2] is True for item in selected),
            'acquired_after_connect_attempt': sum(item[2] is False for item in selected),
            'acquisition_unknown': sum(item[2] is None for item in selected),
            'outcomes': dict(sorted(collections.Counter(item[3] for item in selected).items())),
            'http_statuses': dict(sorted(collections.Counter(str(item[4]) for item in selected if item[4] is not None).items())),
            'response_headers_ms': metrics([s['response_headers'] for _, s, _, _, _ in selected if 'response_headers' in s]),
            'response_headers_ms_by_outcome': {
                outcome: metrics([s['response_headers'] for _, s, _, result, _ in selected
                                  if result == outcome and 'response_headers' in s])
                for outcome in ('completed', 'failed', 'pending', 'unknown')},
            'tls_ms': metrics([s['tls_end'] - s['tls_start'] for _, s, _, _, _ in selected
                               if 'tls_end' in s and 'tls_start' in s and s['tls_end'] >= s['tls_start']])}
    control_summary = {}
    for path in ('tun', 'socks'):
        selected = [entry for entry in controls if entry[0] == path]
        control_summary[path] = {
            'attempts': len(selected),
            'completed': sum(outcome == 'completed' for _, outcome, _, _ in selected),
            'failed': sum(outcome == 'failed' for _, outcome, _, _ in selected),
            'writer_exact_size': sum(key in progress_records and progress_records[key] == expected
                                     for _, _, key, expected in selected if expected is not None),
            'complete_response_ms': metrics([trace_records[key] for _, outcome, key, _ in selected
                                            if outcome == 'completed' and trace_records.get(key) is not None])}
    return {'terminal_transfer_events': len(ends) if ends else len(legacy),
            'recovered_legacy_successes': len(recovered) if ends else 0,
            'ambiguous_legacy_successes': ambiguous,
            'completed_transfers': sum(kind.startswith('completed_') for kind, _ in transfers),
            'failed_transfers': sum(kind.startswith('failed_') for kind, _ in transfers),
            'unknown_transfer_intervals': sum(span is None for _, span in transfers),
            'download_body_observations': {
                'n': len(bodies),
                'exact_size': sum(received == expected for received, expected in bodies),
                'partial': sum(received < expected for received, expected in bodies),
                'oversized': sum(received > expected for received, expected in bodies),
                'partial_received_bytes': metrics([received for received, expected in bodies if received < expected])},
            'probe_hosts': result, 'http_stages': stage_summary, 'upload_control_paths': control_summary,
            'limits': ['One log is one run. Never merge monotonic timestamps across runs or boots.',
                       'Valid-response percentiles include slow responses; failures are counted separately.',
                       'Old logs omit failed bulk intervals. Unknown intervals never prove overlap.',
                       'Ambiguous legacy/terminal matches are not double-counted; inspect the original log.',
                       'Body observations count bytes returned to the test reader, not all network traffic.',
                       'Upload writer progress proves local acceptance, not remote receipt of the full file.',
                       'Completed HTTP calls can have invalid status and are not health success.',
                       'Headers timing is not page readiness. OkHttp connection reuse is not an Xray outbound tag.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('log', type=Path)
    args = parser.parse_args()
    with args.log.open(encoding='utf-8', errors='replace') as stream:
        print(json.dumps(summarize(stream), indent=2, sort_keys=True))


if __name__ == '__main__':
    main()
