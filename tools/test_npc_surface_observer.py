import unittest
import numpy as np
from npc_surface_observer import associate


class AssociationTest(unittest.TestCase):
    def setUp(self):
        self.frame = dict(schema='numen.surface_frame.v1', frame_id='f1', world_session='s1', dimension='minecraft:overworld',
                          companion='npc', captured_at_ms=123, grid_origin=[0, 0, 0], grid_shape=[32,16,32],
                          objects=[dict(object_id='minecraft:overworld:1,2,3', block_pos=[1,2,3],
                                        native_block_id='minecraft:stone', native_block_state='stone', sample_indices=[0,1])])
        self.prob = np.zeros((9,90,160), dtype=np.float32)
        self.prob[3] = 1  # RGB may confidently disagree with the native material.

    def test_native_identity_survives_visual_disagreement_and_hidden_pixels_are_excluded(self):
        self.prob[1,0,2] = 100  # Not in a verified surface: must not contribute.
        result = associate(self.frame,self.prob,[None,.5,.5,.5,None,None,None,None,None])
        obj = result['objects'][0]
        self.assertEqual(obj['native_block_id'],'minecraft:stone')
        self.assertEqual(obj['accepted_visual_votes'],{'3':2})
        self.assertEqual(obj['mean_visual_probabilities'][1],0)
        self.assertEqual(obj['frame_id'],'f1')

    def test_duplicate_or_out_of_range_surface_indices_are_rejected(self):
        for indices in ([0,0],[-1],[14400]):
            self.frame['objects'][0]['sample_indices']=indices
            with self.assertRaises(ValueError):
                associate(self.frame,self.prob,[None]*9)


if __name__ == '__main__':
    unittest.main()
