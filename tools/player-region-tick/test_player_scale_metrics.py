#!/usr/bin/env python3
"""Scale monitor proof controls: native rings must not multiply samples or hide data loss."""
import copy
import unittest
from verify_player_scale import describe, native_window, ring_updates


def samples():
    ring = [1_000_000] * 100
    rows = []
    for sequence in range(25):
        for tick in range(sequence*4, sequence*4+4):
            ring[tick % 100] = 2_000_000 + tick * 1000
        rows.append(dict(seq=sequence+1, monitorNanos=sequence*200_000_000, tickTimesNanos=ring.copy(),
                         sampleDurationNanos=1_000_000, heapUsedBytes=100_000_000, gc=[dict(name='GC', count=0, millis=0)],
                         threads=[dict(id=1, name='Server thread', allocatedBytes=sequence*100, cpuNanos=sequence*1000,
                                       stack=['net.neoforged.neoforge.server.threading.region.RegionTaskBatch$Barrier.awaitCompletion(RegionTaskBatch.java:300)'])]))
    return rows


class PlayerScaleMetricsTest(unittest.TestCase):
    def test_native_ring_counts_only_updated_slots_not_every_historical_slot_per_poll(self):
        values, intervals = ring_updates(samples())
        self.assertEqual(96, len(values))
        self.assertEqual(96, sum(row['observedUpdates'] for row in intervals))
        self.assertAlmostEqual(2.099, max(values))

    def test_circular_wrap_remains_one_update_per_slot(self):
        rows = samples()
        for index in range(25, 50):
            row = copy.deepcopy(rows[-1])
            row.update(seq=index+1, monitorNanos=index*200_000_000)
            for tick in range(index*4, index*4+4):
                row['tickTimesNanos'][tick % 100] = 2_000_000+tick*1000
            rows.append(row)
        self.assertEqual(196, len(ring_updates(rows)[0]))

    def test_repeated_same_ring_does_not_fake_extra_ticks(self):
        rows = samples()
        for row in rows[1:]:
            row['tickTimesNanos'] = rows[0]['tickTimesNanos'].copy()
        with self.assertRaisesRegex(AssertionError, 'Insufficient actual complete-tick'):
            ring_updates(rows)

    def test_sampling_gap_or_whole_ring_overwrite_refuses_per_tick_claim(self):
        for corrupt in ['gap', 'overwrite', 'negative']:
            with self.subTest(corrupt=corrupt):
                rows = samples()
                if corrupt == 'gap':
                    rows[3]['monitorNanos'] += 5_000_000_000
                elif corrupt == 'overwrite':
                    rows[3]['tickTimesNanos'] = [5_000_000] * 100
                else:
                    rows[3]['tickTimesNanos'][0] = -1
                with self.assertRaises(AssertionError):
                    ring_updates(rows)

    def test_percentiles_are_explicit_nearest_rank_with_ns_conversion_outside(self):
        summary = describe(range(1,101))
        self.assertEqual(50.5, summary['mean'])
        self.assertEqual(95, summary['p95'])
        self.assertEqual(99, summary['p99'])
        for invalid in [[], [-1], [float('nan')]]:
            with self.assertRaises(AssertionError): describe(invalid)

    def test_native_allocation_and_barrier_waiting_keep_limits_in_report(self):
        report = native_window(samples())
        self.assertEqual(2400, report['allocatedBytesObserved'])
        self.assertTrue(report['allocationIsLowerBound'])
        self.assertIsNone(report['barrierOverheadNanos'])
        self.assertEqual(1, report['sampledBarrierWaitingFraction'])
        self.assertGreater(report['sampledBarrierWaitingStacks'], 0)

    def test_negative_native_allocation_delta_is_not_hidden(self):
        rows = samples()
        rows[5]['threads'][0]['allocatedBytes'] = 0
        with self.assertRaisesRegex(AssertionError, 'allocation counter regressed'):
            native_window(rows)


if __name__ == '__main__':
    unittest.main()
