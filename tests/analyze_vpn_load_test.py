import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('analyze_vpn_load', Path(__file__).resolve().parents[1] / 'scripts/analyze_vpn_load.py')
analyzer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(analyzer)


class AnalyzeVpnLoadTest(unittest.TestCase):
    def test_slow_valid_replies_and_errors_remain_visible(self):
        result = analyzer.summarize([
            'LIVE_VPN download_bytes=8 request_start_ms=100 request_end_ms=500',
            'LIVE_VPN phase=during_transfer_1 endpoint=www.gstatic.com usable=true elapsed_ms=200 request_start_ms=150 request_end_ms=350',
            'LIVE_VPN phase=during_transfer_2 endpoint=www.gstatic.com usable=true elapsed_ms=13000 request_start_ms=400 request_end_ms=13400',
            'LIVE_VPN phase=during_transfer_3 endpoint=www.gstatic.com error=SocketTimeoutException elapsed_ms=30000',
            'LIVE_VPN phase=during_transfer_3 endpoint=cp.cloudflare.com usable=false elapsed_ms=90'])
        google = result['probe_hosts']['www.gstatic.com']
        self.assertEqual((3, 2, 1), (google['attempts'], google['valid_responses'], google['errors']))
        self.assertEqual(13000, google['valid_elapsed_ms']['p95_nearest_rank'])
        self.assertEqual(1, google['valid_elapsed_ms']['over_6000ms'])
        self.assertEqual({'inside_completed_download': 1, 'interval_unknown': 1,
                          'no_known_transfer_contains_probe': 1}, google['complete_overlap'])
        self.assertEqual(1, result['probe_hosts']['cp.cloudflare.com']['invalid_responses'])

    def test_terminal_events_include_failed_transfers_without_duplicate_success(self):
        result = analyzer.summarize([
            'LIVE_VPN event=bulk_end kind=download outcome=completed request_start_ms=101 request_end_ms=199',
            'LIVE_VPN download_bytes=8 request_start_ms=100 request_end_ms=200',
            'LIVE_VPN event=bulk_end kind=upload outcome=failed request_start_ms=300 request_end_ms=500',
            'LIVE_VPN phase=during_transfer_1 endpoint=example.com usable=true elapsed_ms=50 request_start_ms=350 request_end_ms=400'])
        self.assertEqual(2, result['terminal_transfer_events'])
        self.assertEqual(1, result['completed_transfers'])
        self.assertEqual(1, result['failed_transfers'])
        self.assertEqual({'inside_failed_upload': 1}, result['probe_hosts']['example.com']['complete_overlap'])

    def test_trace_lines_never_count_as_extra_probe_attempts(self):
        result = analyzer.summarize([
            'LIVE_VPN phase=during_transfer_1 endpoint=example.com usable=true elapsed_ms=80',
            'LIVE_VPN event=http_timing phase=during_transfer_1 endpoint=example.com elapsed_ms=80 acquired_without_connect=true stages=request_sent:5,response_headers:60,response_body_end:80'])
        self.assertEqual(1, result['probe_hosts']['example.com']['attempts'])
        self.assertEqual(1, result['http_stages']['example.com']['calls'])
        self.assertEqual(60, result['http_stages']['example.com']['response_headers_ms']['max'])

    def test_never_prints_untrusted_input_or_unknown_hosts(self):
        result = analyzer.summarize([
            'LIVE_VPN phase=during_transfer_1 endpoint=secret.example usable=true error=private-secret path=private-token',
            'LIVE_VPN event=http_timing endpoint=example.com outcome=private-secret stages=private-token:10,response_headers:20'])
        self.assertNotIn('secret', json.dumps(result))
        self.assertNotIn('private', json.dumps(result))

    def test_interval_boundaries_and_unknown_clock(self):
        for start, end, contained in [(100, 200, True), (110, 190, True), (90, 110, False), (190, 210, False), (150, 150, False)]:
            result = analyzer.summarize([
                'LIVE_VPN event=bulk_end kind=download outcome=failed request_start_ms=100 request_end_ms=200',
                f'LIVE_VPN phase=during_transfer_1 endpoint=example.com usable=true elapsed_ms=10 request_start_ms={start} request_end_ms={end}'])
            overlap = result['probe_hosts']['example.com']['complete_overlap']
            self.assertEqual(contained, 'inside_failed_download' in overlap)
        result = analyzer.summarize(['LIVE_VPN event=bulk_end kind=download outcome=failed request_start_ms=unknown request_end_ms=unknown'])
        self.assertEqual(1, result['unknown_transfer_intervals'])

    def test_empty_or_invalid_input_does_not_invent_measurements(self):
        result = analyzer.summarize(['LIVE_VPN phase=during_transfer_1 endpoint=example.com usable=true elapsed_ms=oops',
                                     'phase=during_transfer_1 endpoint=example.com usable=true elapsed_ms=10'])
        self.assertEqual(0, result['probe_hosts']['example.com']['valid_elapsed_ms']['n'])
        self.assertIsNone(analyzer.summarize([])['probe_hosts']['example.com']['valid_elapsed_ms']['median'])
        self.assertEqual(19, analyzer.metrics(list(range(1, 21)))['p95_nearest_rank'])

    def test_partial_body_bytes_never_become_completed_transfers(self):
        result = analyzer.summarize([
            'LIVE_VPN event=download_body received_bytes=0 expected_bytes=8',
            'LIVE_VPN event=download_body received_bytes=3 expected_bytes=8',
            'LIVE_VPN event=download_body received_bytes=8 expected_bytes=8',
            'LIVE_VPN event=download_body received_bytes=9 expected_bytes=8',
            'LIVE_VPN event=download_body received_bytes=unknown expected_bytes=8',
            'LIVE_VPN event=bulk_end kind=download outcome=failed request_start_ms=10 request_end_ms=20'])
        self.assertEqual(0, result['completed_transfers'])
        self.assertEqual(1, result['failed_transfers'])
        observed = result['download_body_observations']
        self.assertEqual((4, 1, 2, 1), (observed['n'], observed['exact_size'], observed['partial'], observed['oversized']))
        self.assertEqual(3, observed['partial_received_bytes']['max'])
        self.assertEqual(2, observed['partial_received_bytes']['n'])


if __name__ == '__main__':
    unittest.main()
