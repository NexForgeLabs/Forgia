#!/usr/bin/env python3
"""Proof-tool controls derived from a real installed-server corpus, not player-worker tests."""
import argparse
import copy
import json
from pathlib import Path
import tempfile
import unittest
from verify_oracle import validate


class OracleEvidenceControls(unittest.TestCase):
    corpus = None

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'run').mkdir()
        for relative in ['run/result.json', 'run/inputs.json', 'run/packets.ndjson', 'run/clients.stdout.log', 'player-oracle.ndjson']:
            (self.root / relative).write_bytes((self.corpus / relative).read_bytes())
        if (self.corpus / 'player-criterion-storage.json').exists():
            (self.root / 'player-criterion-storage.json').write_bytes((self.corpus / 'player-criterion-storage.json').read_bytes())
        if (self.corpus / 'player-planning.json').exists():
            (self.root / 'player-planning.json').write_bytes((self.corpus / 'player-planning.json').read_bytes())
        self.rows = [json.loads(line) for line in (self.root / 'player-oracle.ndjson').read_text().splitlines()]

    def write_rows(self):
        for index, row in enumerate(self.rows, start=1):
            row['seq'] = index
        (self.root / 'player-oracle.ndjson').write_text(''.join(json.dumps(row) + '\n' for row in self.rows))

    def test_actual_unmodified_corpus_passes(self):
        self.assertEqual('passed', validate(self.root)['status'])

    def test_missing_complete_connection_entry_is_not_dotick_coverage(self):
        self.rows = [row for row in self.rows if row['kind'] != 'CONNECTION_START']
        self.write_rows()
        with self.assertRaises(AssertionError):
            validate(self.root)

    def test_duplicate_real_damage_event_is_rejected(self):
        index = next(index for index, row in enumerate(self.rows) if row['kind'] == 'DAMAGE_POST')
        self.rows.insert(index, copy.deepcopy(self.rows[index]))
        self.write_rows()
        with self.assertRaises(AssertionError):
            validate(self.root)

    def test_layout_coordinate_does_not_replace_authoritative_owner(self):
        for row in self.rows:
            if 'ownerRegionX' in row:
                row['ownerRegionX'] += 1
        self.write_rows()
        with self.assertRaises(AssertionError):
            validate(self.root)

    def test_snapshot_without_real_client_health_feedback_is_rejected(self):
        packets = [json.loads(line) for line in (self.root / 'run/packets.ndjson').read_text().splitlines()]
        packets = [row for row in packets if not (row['direction'] == 'receive' and row['name'] == 'update_health')]
        (self.root / 'run/packets.ndjson').write_text(''.join(json.dumps(row) + '\n' for row in packets))
        with self.assertRaises(AssertionError):
            validate(self.root)

    def test_old_respawn_instance_is_not_allowed_to_start_again(self):
        clone = next(row for row in self.rows if row['kind'] == 'CLONE')
        old_instance = int(clone['detail'].split('=')[1])
        stale = copy.deepcopy(next(row for row in self.rows if row.get('instance') == old_instance and row['kind'] == 'ENTITY_START'))
        stale['tick'] = self.rows[-1]['tick']
        self.rows.insert(-1, stale)
        self.write_rows()
        with self.assertRaises(AssertionError):
            validate(self.root)

    def test_missing_real_criterion_progress_event_is_rejected(self):
        if not any(row['kind'] == 'CRITERION_PROGRESS' for row in self.rows):
            self.skipTest('Corpus has no criterion proof; this control requires --criterion-proof')
        self.rows = [row for row in self.rows if row['kind'] != 'CRITERION_PROGRESS']
        self.write_rows()
        with self.assertRaises(AssertionError):
            validate(self.root)

    def test_repeated_original_reward_function_result_is_rejected(self):
        path = self.root / 'run/result.json'
        result = json.loads(path.read_text())
        if 'criterionProof' not in result:
            self.skipTest('Corpus has no criterion proof; this control requires --criterion-proof')
        result['criterionProof']['afterRevoke']['OracleA'] = 3
        path.write_text(json.dumps(result) + '\n')
        with self.assertRaises(AssertionError):
            validate(self.root)

    def test_incomplete_trace_is_not_a_pass(self):
        self.rows.pop()
        self.write_rows()
        with self.assertRaises(AssertionError):
            validate(self.root)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', type=Path, required=True)
    args, remaining = parser.parse_known_args()
    OracleEvidenceControls.corpus = args.server.resolve()
    unittest.main(argv=[__file__, *remaining], verbosity=2)
