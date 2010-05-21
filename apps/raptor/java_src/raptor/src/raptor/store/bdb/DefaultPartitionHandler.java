package raptor.store.bdb;

import java.util.HashMap;

import raptor.util.ConsistentHash;
import raptor.util.RaptorUtils;

import com.sleepycat.db.Database;
import com.sleepycat.db.DatabaseEntry;

public class DefaultPartitionHandler implements com.sleepycat.db.PartitionHandler {
    
    private ConsistentHash<String> chash;
    private HashMap<String, Integer> map;
    
    public DefaultPartitionHandler(String[] partitionDirs) throws Exception {
        map = new HashMap<String, Integer>();
        chash = RaptorUtils.createConsistentHash(partitionDirs);
        for(int i=0; i<partitionDirs.length; i++) {
            map.put(partitionDirs[i], new Integer(i));
        }
    }
    
    public int partition(Database db, DatabaseEntry key) {
        try {
            return map.get(chash.get(new String(key.getData())));
        } catch (Exception ex) {
            ex.printStackTrace();
            return 0;
        }
    }
}