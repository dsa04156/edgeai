"""Real API/DB regression: a saturated load driver retains its post-window database evidence."""
import argparse
import json
from pathlib import Path
import runpy
import sys
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    driver = runpy.run_path(str(Path(__file__).with_name('test-device-load.py')))
    namespace = driver['main'].__globals__
    run_window = namespace['run_window']

    def saturated(client, devices, seconds, interval, workers):
        class DelayedClient:
            def request(self, *positional, **keywords):
                time.sleep(.3)
                return client.request(*positional, **keywords)
        # Only the test client is slowed. Every sent request still reaches the actual API/DB.
        return run_window(DelayedClient(), devices, seconds, interval, workers=1)

    namespace['run_window'] = saturated
    sys.argv = ['test-device-load.py', '--counts', '10', '--seconds', '2', '--interval', '1',
                '--measure-only', '--report', str(args.report)]
    code = driver['main']()
    report = json.loads(args.report.read_text())
    assert code == 1 and report['status'] == 'FAIL' and report['performanceAcceptance'] == 'UNSET'
    assert report['ownedApiStopped'] and report['ownedDatabaseRemoved']
    stage, = report['stages']
    summary = stage['summary']
    assert summary['offered'] == 22 and summary['sent'] + summary['dropped'] == 22
    assert summary['dropped'] > 0 and summary['unexpected'] == 0
    assert 'integrity' not in stage, 'A failed load must not claim full correctness acceptance'
    snapshot = stage['postWindowDatabase']
    writes = stage['operations']['observation']['succeeded']
    assert snapshot['devices'] == snapshot['sessions'] == 10
    assert snapshot['observations'] == 10 + writes
    assert snapshot['audit']['requests'] == 35 + writes
    assert 0 < snapshot['audit']['outcomes'] <= snapshot['audit']['requests']
    assert snapshot['audit']['invalidActors'] == snapshot['audit']['handlerFailures'] == 0
    assert snapshot['auditTransactions'] > 0 and snapshot['bytes'] > 0
    assert 'postWindowDatabaseError' not in stage
    print('PASS: real queue saturation remains FAIL; committed observations/audit counts retained; owned API/DB removed')


if __name__ == '__main__':
    main()
