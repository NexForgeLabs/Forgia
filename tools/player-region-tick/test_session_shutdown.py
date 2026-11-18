#!/usr/bin/env python3
"""Regression for interrupted RCON response poisoning an isolated test shutdown."""
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock
from run_worker_lifecycle import Session


class SessionShutdownTest(unittest.TestCase):
    def test_rcon_reply_error_preserved_before_console_stop_and_all_handles_close(self):
        with tempfile.TemporaryDirectory(prefix='forgia-shutdown-control-') as directory:
            s=Session.__new__(Session)
            s.output=Path(directory);s.boot=1;s.exits=[]
            process=Mock();process.poll.return_value=None;process.returncode=0;process.stdin=io.BytesIO()
            s.process=process;s.rcon=Mock();rcon=s.rcon;s.log=io.StringIO();log=s.log
            s.command=Mock(side_effect=RuntimeError('Unexpected command reply id/type'))
            s.stop()
            self.assertEqual(b'stop\n',process.stdin.getvalue())
            process.wait.assert_called_once_with(timeout=45)
            process.terminate.assert_not_called();process.kill.assert_not_called()
            self.assertEqual([0],s.exits);self.assertIsNone(s.process);self.assertIsNone(s.rcon)
            rcon.close.assert_called_once();self.assertTrue(log.closed)
            record=json.loads((Path(directory)/'boot-1.stop-fallback.json').read_text())
            self.assertIn('Unexpected command reply id/type',record['error'])
            self.assertEqual('owned server console stop',record['fallback'])


if __name__=='__main__':unittest.main()
