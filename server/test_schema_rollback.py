"""The additive location table must not break device deletion after rollback."""
import sqlite3
import unittest
from pathlib import Path


class LocationSchemaRollbackTests(unittest.TestCase):
    def test_older_relay_device_delete_cleans_location_fence(self):
        con=sqlite3.connect(":memory:")
        try:
            con.executescript(Path(__file__).with_name("schema.sql").read_text())
            con.execute("INSERT INTO devices(id,token_hash,name,created_at) VALUES('node','hash','node',1)")
            con.execute("INSERT INTO location_purge(device_id,purged_at,active_epoch) VALUES('node',2,'epoch')")
            # Older code deletes the device without knowing about location_purge.
            con.execute("DELETE FROM devices WHERE id='node'")
            self.assertEqual(con.execute("SELECT COUNT(*) FROM location_purge").fetchone()[0],0)
        finally:con.close()


if __name__=="__main__":
    unittest.main()
