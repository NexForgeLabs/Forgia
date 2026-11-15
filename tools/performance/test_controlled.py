#!/usr/bin/env python3
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('export_controlled', Path(__file__).with_name('export_controlled.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ControlledExportTest(unittest.TestCase):
    def fixture(self):
        return dict(outcome='PASS', profiled=False, count=64, warmupTicks=400, windowTicks=400,
                    version='test', universalSha256='a' * 64, bridge=True, diagnostics=False, stock=False,
                    results=[dict(window=0, ticks=400, outputItems=128, stateBeforeSha256='a', stateAfterSha256='b',
                                  metrics=dict(mspt=dict(mean=1), observedTps=20, serverAndWorkerAllocatedBytes=1048576,
                                               allocationObservationSeconds=1, workerCpuSeconds=.5, gcCount=0, gcMillis=0),
                                  threadMetrics=dict(mainCpuSeconds=.2, observationSeconds=1, threads=[dict(blockedEntrances=0)]))])

    def check_pair(self, mutation=None):
        with tempfile.TemporaryDirectory() as directory:
            a, b = Path(directory) / 'a.json', Path(directory) / 'b.json'
            data = self.fixture()
            a.write_text(json.dumps(data))
            second = copy.deepcopy(data)
            if mutation:
                mutation(second)
            b.write_text(json.dumps(second))
            return module.export([a, b])

    def test_identical_work_and_state_export(self):
        self.assertTrue(self.check_pair()['exactNbtAndOutputEquivalence'])

    def test_different_saved_state_is_refused(self):
        with self.assertRaises(ValueError):
            self.check_pair(lambda data: data['results'][0].update(stateAfterSha256='changed'))

    def test_different_output_is_refused(self):
        with self.assertRaises(ValueError):
            self.check_pair(lambda data: data['results'][0].update(outputItems=127))

    def test_profiled_or_failed_runs_are_refused(self):
        for key, value in [('profiled', True), ('outcome', 'FAIL')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.check_pair(lambda data: data.update({key: value}))


if __name__ == '__main__':
    unittest.main()
