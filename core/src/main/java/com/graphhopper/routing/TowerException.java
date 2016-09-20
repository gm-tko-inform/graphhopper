package com.graphhopper.routing;

import com.graphhopper.storage.index.QueryResult;

/**
 * <p/>
 *
 * @author vselivanov
 */
public class TowerException extends RuntimeException {
    private QueryResult res;
    public TowerException(QueryResult res) {
        this.res = res;
    }

    public QueryResult getRes() {
        return res;
    }
}
