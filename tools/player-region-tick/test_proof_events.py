import json
from pathlib import Path
import tempfile
import unittest
from proof_events import read_complete_events


class ProofEventTest(unittest.TestCase):
    def test_partial_live_write_is_not_a_committed_corrupt_record(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'events.ndjson'
            path.write_text('{"event":"body"}\n{"event":"joined","diagnostics":"part')
            self.assertEqual([{'event':'body'}], read_complete_events(path))
            with path.open('a') as stream: stream.write('ial"}\n')
            self.assertEqual([{'event':'body'}, {'event':'joined','diagnostics':'partial'}], read_complete_events(path))

    def test_malformed_committed_record_still_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'events.ndjson'
            path.write_text('{"event":"unterminated}\n')
            with self.assertRaises(json.JSONDecodeError): read_complete_events(path)

    def test_uncreated_producer_file_is_empty(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual([], read_complete_events(Path(directory)/'not-created'))


if __name__=='__main__': unittest.main()
