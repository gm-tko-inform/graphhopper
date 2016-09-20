/*
 *  Licensed to GraphHopper and Peter Karich under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.GraphExtension;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.storage.TurnCostExtension;
import com.graphhopper.storage.index.QueryResult;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.BBox;
import com.graphhopper.util.shapes.GHPoint;
import com.graphhopper.util.shapes.GHPoint3D;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.procedure.TIntProcedure;
import gnu.trove.procedure.TObjectProcedure;
import gnu.trove.set.hash.TIntHashSet;
import org.geojson.Feature;
import org.geojson.FeatureCollection;
import org.geojson.Point;

import java.util.*;

/**
 * A class which is used to query the underlying graph with real GPS points. It does so by
 * introducing virtual nodes and edges. It is lightweight in order to be created every time a new
 * query comes in, which makes the behaviour thread safe.
 * <p/>
 *
 * @author Peter Karich
 */
public class QueryGraph implements Graph {
    private final Graph mainGraph;
    private final NodeAccess mainNodeAccess;
    private final int mainNodes;
    private final int mainEdges;
    private final QueryGraph baseGraph;
    private final GraphExtension wrappedExtension;
    private FlagEncoder encoder;
    private List<QueryResult> queryResults;
    /**
     * Virtual edges are created between existing graph and new virtual tower nodes. For every
     * virtual node there are 4 edges: base-snap, snap-base, snap-adj, adj-snap.
     */
    private List<EdgeIteratorState> virtualEdges;
    private final static int VE_BASE = 0, VE_BASE_REV = 1, VE_ADJ = 2, VE_ADJ_REV = 3;

    /**
     * Store lat,lon of virtual tower nodes.
     */
    private PointList virtualNodes;
    private static boolean SYSTEM_DEBUG = false;

    public FlagEncoder getEncoder() {
        return encoder;
    }

    public void setEncoder(FlagEncoder encoder) {
        this.encoder = encoder;
    }

    public QueryGraph(Graph graph) {
        mainGraph = graph;
        mainNodeAccess = graph.getNodeAccess();
        mainNodes = graph.getNodes();
        mainEdges = graph.getAllEdges().getCount();

        if (mainGraph.getExtension() instanceof TurnCostExtension)
            wrappedExtension = new QueryGraphTurnExt(this);
        else
            wrappedExtension = mainGraph.getExtension();

        // create very lightweight QueryGraph which uses variables from this QueryGraph (same virtual edges)
        baseGraph = new QueryGraph(graph.getBaseGraph(), this);
    }

    /**
     * See 'lookup' for further variables that are initialized
     */
    private QueryGraph(Graph graph, QueryGraph superQueryGraph) {
        mainGraph = graph;
        baseGraph = this;
        wrappedExtension = superQueryGraph.wrappedExtension;
        mainNodeAccess = graph.getNodeAccess();
        mainNodes = superQueryGraph.mainNodes;
        mainEdges = superQueryGraph.mainEdges;
    }

    /**
     * Convenient method to initialize this QueryGraph with the two specified query results.
     */
    public QueryGraph lookup(QueryResult fromRes, QueryResult toRes) {
        List<QueryResult> results = new ArrayList<QueryResult>(2);
        results.add(fromRes);
        results.add(toRes);
        lookup(results);
        return this;
    }

    /**
     * For all specified query results calculate snapped point and set closest node and edge to a
     * virtual one if necessary. Additionally the wayIndex can change if an edge is swapped.
     */
    public void lookup(List<QueryResult> resList) {
        if (isInitialized())
            throw new IllegalStateException("Call lookup only once. Otherwise you'll have problems for queries sharing the same edge.");

        for (int i = 0; i < resList.size(); i++) {
            resList.get(i).setFirst(i == 0);
            resList.get(i).setLast(i == resList.size() - 1);
        }


        // initialize all none-final variables
        virtualEdges = new ArrayList<EdgeIteratorState>(resList.size() * 2);
        virtualNodes = new PointList(resList.size(), mainNodeAccess.is3D());
        queryResults = new ArrayList<QueryResult>(resList.size());
        baseGraph.virtualEdges = virtualEdges;
        baseGraph.virtualNodes = virtualNodes;
        baseGraph.queryResults = queryResults;

        TIntObjectMap<List<QueryResult>> edge2res = new TIntObjectHashMap<List<QueryResult>>(resList.size());

        // Phase 1
        // calculate snapped point and swap direction of closest edge if necessary
        int index = 0;
        for (QueryResult res : resList) {
            index++;
            // Do not create virtual node for a query result if it is directly on a tower node or not found
            EdgeIteratorState closestEdge = res.getClosestEdge();

            if (res.getSnappedPosition() == QueryResult.Position.TOWER) {
                // Временный хак. Сдвигаю на мизер точку
                // todo - это убрать, а вставить нормальную обработку точек, попадающих в узел
                throw new TowerException(res);
            }


            if (closestEdge == null)
                throw new IllegalStateException("Do not call QueryGraph.lookup with invalid QueryResult " + res);

            int base = closestEdge.getBaseNode();

            // Force the identical direction for all closest edges. 
            // It is important to sort multiple results for the same edge by its wayIndex
            boolean doReverse = base > closestEdge.getAdjNode();
//            if (index == 1) {
//                doReverse = true;
//                res.setSnappedPosition(QueryResult.Position.PILLAR);
////            res.setWayIndex(-1);
////                res.setWayIndex(0);
//            }

            if (base == closestEdge.getAdjNode()) {
                // check for special case #162 where adj == base and force direction via latitude comparison
                PointList pl = closestEdge.fetchWayGeometry(0);
                if (pl.size() > 1)
                    doReverse = pl.getLatitude(0) > pl.getLatitude(pl.size() - 1);
            }

            if (doReverse) {
                closestEdge = closestEdge.detach(true);
                PointList fullPL = closestEdge.fetchWayGeometry(3);
                res.setClosestEdge(closestEdge);
                if (res.getSnappedPosition() == QueryResult.Position.PILLAR)
                    // ON pillar node                
                    res.setWayIndex(fullPL.getSize() - res.getWayIndex() - 1);
                else
                    // for case "OFF pillar node"
                    res.setWayIndex(fullPL.getSize() - res.getWayIndex() - 2);

                if (res.getWayIndex() < 0)
                    throw new IllegalStateException("Problem with wayIndex while reversing closest edge:" + closestEdge + ", " + res);
            }

            // find multiple results on same edge
            int edgeId = closestEdge.getEdge();
            List<QueryResult> list = edge2res.get(edgeId);
            if (list == null) {
                list = new ArrayList<QueryResult>(5);
                edge2res.put(edgeId, list);
            }
            list.add(res);
        }


        // Phase 2 - now it is clear which points cut one edge
        // 1. create point lists
        // 2. create virtual edges between virtual nodes and its neighbor (virtual or normal nodes)
        edge2res.forEachValue(new TObjectProcedure<List<QueryResult>>() {
            @Override
            public boolean execute(List<QueryResult> results) {
                // we can expect at least one entry in the results
                EdgeIteratorState closestEdge = results.get(0).getClosestEdge();
                final PointList fullPL = closestEdge.fetchWayGeometry(3);
                int baseNode = closestEdge.getBaseNode();
                // sort results on the same edge by the wayIndex and if equal by distance to pillar node
                Collections.sort(results, new Comparator<QueryResult>() {
                    @Override
                    public int compare(QueryResult o1, QueryResult o2) {
                        int diff = o1.getWayIndex() - o2.getWayIndex();
                        if (diff == 0) {
                            // sort by distance from snappedPoint to fullPL.get(wayIndex) if wayIndex is identical
                            GHPoint p1 = o1.getSnappedPoint();
                            GHPoint p2 = o2.getSnappedPoint();
                            if (p1.equals(p2))
                                return 0;

                            double fromLat = fullPL.getLatitude(o1.getWayIndex());
                            double fromLon = fullPL.getLongitude(o1.getWayIndex());
                            if (Helper.DIST_PLANE.calcNormalizedDist(fromLat, fromLon, p1.lat, p1.lon)
                                    > Helper.DIST_PLANE.calcNormalizedDist(fromLat, fromLon, p2.lat, p2.lon))
                                return 1;
                            return -1;
                        }
                        return diff;
                    }
                });


                GHPoint3D prevPoint = fullPL.toGHPoint(0);
                int adjNode = closestEdge.getAdjNode();
                long reverseFlags = closestEdge.detach(true).getFlags();
                int prevWayIndex = 1;
                int prevNodeId = baseNode;
                int virtNodeId = virtualNodes.getSize() + mainNodes;
                boolean addedEdges = false;

                // Create base and adjacent PointLists for all none-equal virtual nodes.
                // We do so via inserting them at the correct position of fullPL and cutting the
                // fullPL into the right pieces.
                QueryResult res = null;


                for (int counter = 0; counter < results.size(); counter++) {
                    res = results.get(counter);


                    if (res.getClosestEdge().getBaseNode() != baseNode)
                        throw new IllegalStateException("Base nodes have to be identical but were not: " + closestEdge + " vs " + res.getClosestEdge());

                    GHPoint3D currSnapped = res.getSnappedPoint();


                    // no new virtual nodes if exactly the same snapped point
                    if (prevPoint.equals(currSnapped)) {
                        res.setClosestNode(prevNodeId);
                        continue;
                    }

                    queryResults.add(res);
                    createEdges(prevPoint, prevWayIndex,
                            res.getSnappedPoint(), res.getWayIndex(),
                            fullPL, closestEdge, prevNodeId, virtNodeId, reverseFlags, res);

                    virtualNodes.add(currSnapped.lat, currSnapped.lon, currSnapped.ele);

                    // add edges again to set adjacent edges for newVirtNodeId
                    // Если несколько точек запроса лежат на одном ребре, то
                    if (addedEdges) {
                        // [end] -- [...] -- [res.snapped] -- [prevRes.snapped] -- [...] -- [start]

                        // Добавляю связь с крайними точками. Предыдущую от текущего разреза - к концу вершины
                        // [prevRes.snapped] to [end]
                        createEdges(
                                prevPoint,                              // prevSnapped
                                prevWayIndex,
                                fullPL.toGHPoint(fullPL.getSize() - 1), // curSnapped
                                fullPL.getSize() - 2,
                                fullPL,
                                closestEdge,
                                virtNodeId - 1,                         // prevNodeId
                                adjNode,                                // curNodeId
                                reverseFlags,
                                results.get(counter - 1)
                        );

                        // И текущую к первой точке
                        // [res.snapped] to [start]
                        createEdges(
                                fullPL.toGHPoint(0),                    // prevSnapped
                                0,
                                res.getSnappedPoint(),                  // curSnapped
                                res.getWayIndex(),
                                fullPL,
                                closestEdge,
                                baseNode,                               // prevNodeId
                                virtNodeId,                             // curNodeId
                                reverseFlags,
                                res
                        );

                    }

                    addedEdges = true;
                    res.setClosestNode(virtNodeId);
                    prevNodeId = virtNodeId;
                    prevWayIndex = res.getWayIndex() + 1;
                    prevPoint = currSnapped;
                    virtNodeId++;
                }

                // two edges between last result and adjacent node are still missing if not all points skipped
                if (addedEdges)
                    createEdges(prevPoint, prevWayIndex, fullPL.toGHPoint(fullPL.getSize() - 1), fullPL.getSize() - 2,
                            fullPL, closestEdge, virtNodeId - 1, adjNode, reverseFlags, res);

                return true;
            }
        });
    }

    @Override
    public Graph getBaseGraph() {
        // Note: if the mainGraph of this QueryGraph is a LevelGraph then ignoring the shortcuts will produce a 
        // huge gap of edgeIds between base and virtual edge ids. The only solution would be to move virtual edges
        // directly after normal edge ids which is ugly as we limit virtual edges to N edges and waste memory or make everything more complex.        
        return baseGraph;
    }

    public boolean isVirtualEdge(int edgeId) {
        return edgeId >= mainEdges;
    }

    public boolean isVirtualNode(int nodeId) {
        return nodeId >= mainNodes;
    }

    class QueryGraphTurnExt extends TurnCostExtension {
        private final TurnCostExtension mainTurnExtension;

        public QueryGraphTurnExt(QueryGraph qGraph) {
            this.mainTurnExtension = (TurnCostExtension) mainGraph.getExtension();
        }

        @Override
        public long getTurnCostFlags(int edgeFrom, int nodeVia, int edgeTo) {
            if (isVirtualNode(nodeVia)) {
                return 0;
            } else if (isVirtualEdge(edgeFrom) || isVirtualEdge(edgeTo)) {
                if (isVirtualEdge(edgeFrom)) {
//                    edgeFrom = queryResults.get((edgeFrom - mainEdges) / 4).getClosestEdge().getEdge();
                    edgeFrom = virtualEdgeIdToBaseEdge.get(edgeFrom).getEdge();
                }
                if (isVirtualEdge(edgeTo)) {
//                    edgeTo = queryResults.get((edgeTo - mainEdges) / 4).getClosestEdge().getEdge();
                    edgeTo = virtualEdgeIdToBaseEdge.get(edgeTo).getEdge();
                }
                return mainTurnExtension.getTurnCostFlags(edgeFrom, nodeVia, edgeTo);

            } else {
                return mainTurnExtension.getTurnCostFlags(edgeFrom, nodeVia, edgeTo);
            }
        }
    }

    private enum TriangleType {
        IN,
        OUT
    }

    Map<Integer, GHPoint> debugPointInfo = new HashMap<Integer, GHPoint>();
    List<GHPoint> debugQueryPoints = new ArrayList<GHPoint>();
    Map<Integer, EdgeIteratorState> virtualEdgeIdToBaseEdge = new HashMap<Integer, EdgeIteratorState>();

    private PointList reverseClonePoints(PointList src) {
        PointList result = new PointList(src.getSize(), src.is3D());
        for (int i = src.getSize() - 1; i >= 0; i--) {
            result.add(src.getLat(i), src.getLon(i), src.getEle(i));
        }
        return result;
    }

    private static final int BASE_ROAD_WIDE = 6;


    GHPoint lastQueryPoint = null;

    private void createEdges(GHPoint3D prevSnapped, int prevWayIndex, GHPoint3D currSnapped, int wayIndex,
                             PointList fullPL, EdgeIteratorState closestEdge,
                             int prevNodeId, int nodeId, long reverseFlags, QueryResult res) {


        // Формирую точки виртуального ребра
        PointList basePoints;
        PointList reversePoints;

        int max = wayIndex + 1;
        // basePoints must have at least the size of 2 to make sure fetchWayGeometry(3) returns at least 2
        PointList edgePoints = new PointList(max - prevWayIndex + 1, mainNodeAccess.is3D());
        edgePoints.add(prevSnapped.lat, prevSnapped.lon, prevSnapped.ele);
        for (int i = prevWayIndex; i < max; i++) {
            edgePoints.add(fullPL, i);
        }
        edgePoints.add(currSnapped.lat, currSnapped.lon, currSnapped.ele);


        // Направление рёбер
        // Устанавливаю правило, что From-To всегда равно из точки ВЕРШИНА в точку ПРОЕКЦИЯ
        // то есть ребро треугольника CB, и оно же всегда является БАЗОЙ, а ребро BC - реверсом
        int nodeC;   // Вершина C
        int nodeB;   // Вершина B

        // Формирую треугольник ABC (ЗАПРОС-ПРОЕКЦИЯ-ВЕРШИНА)
        // Точка A - всегда точка запроса (ЗАПРОС)
        // Точка B - всегда проекция запроса на ребро, то есть виртуальная вершина (ПРОЕКЦИЯ)
        // Точка C - всегда вершина (ВЕРШИНА)
        GHPoint pointA = res.getQueryPoint();
        GHPoint3D pointB = res.getSnappedPoint();
        GHPoint3D pointC;
        boolean isForward;
        boolean isBackward;
        if (pointB.equals(currSnapped)) {
            pointC = prevSnapped;
            nodeC = prevNodeId;
            nodeB = nodeId;
            basePoints = edgePoints;
            reversePoints = reverseClonePoints(edgePoints);
            isForward = encoder.isForward(closestEdge.getFlags());
            isBackward = encoder.isBackward(closestEdge.getFlags());
        } else {
            pointC = currSnapped;
            nodeC = nodeId;
            nodeB = prevNodeId;
            reversePoints = edgePoints;
            basePoints = reverseClonePoints(edgePoints);
            isForward = encoder.isBackward(closestEdge.getFlags());
            isBackward = encoder.isForward(closestEdge.getFlags());
        }

        debugPointInfo.put(nodeC, pointC);
        debugPointInfo.put(nodeB, pointB);
        if (SYSTEM_DEBUG) {
            System.out.println("nodeC = " + nodeC + "; pointC = " + pointC);
            System.out.println("nodeB = " + nodeB + "; pointB = " + pointB);
        }

        if (!debugQueryPoints.contains(pointA)) {
            debugQueryPoints.add(pointA);
        }

        // Поиск косого произведения
        GHPoint vectorA = new GHPoint(pointC.getLon() - pointB.getLon(), pointC.getLat() - pointB.getLat());
        GHPoint vectorB = new GHPoint(pointA.getLon() - pointB.getLon(), pointA.getLat() - pointB.getLat());
        double fiberBundle = vectorA.getLon() * vectorB.getLat() - vectorB.getLon() * vectorA.getLat();

        // Определяю признак того, что угол ABC
        boolean isConnerABCLeft = fiberBundle < 0;

        if (SYSTEM_DEBUG) {
            System.out.println(" from " + nodeC + " to " + nodeB + " type " + isConnerABCLeft + " lanes " + closestEdge.getAdditionalField());
        }


        // Логирую точки ребра
        if (SYSTEM_DEBUG) {
            int i = 1;
            FeatureCollection featureCollection = new FeatureCollection();
            for (GHPoint3D basePoint : basePoints) {
                Feature feature = new Feature();
                feature.setGeometry(new Point(basePoint.getLon(), basePoint.getLat()));
                feature.setProperty("marker-symbol", "" + i++);
                feature.setProperty("marker-size", "small");
                feature.setProperty("marker-color", "#ccffcc");
                featureCollection.add(feature);
            }

            ObjectMapper MAPPER = new ObjectMapper();
            String geoJson = null;
            try {
                geoJson = MAPPER.writer().writeValueAsString(featureCollection);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
            System.out.println(geoJson);

            i = 1;
            featureCollection = new FeatureCollection();
            for (GHPoint3D basePoint : reversePoints) {
                Feature feature = new Feature();
                feature.setGeometry(new Point(basePoint.getLon(), basePoint.getLat()));
                feature.setProperty("marker-symbol", "" + i++);
                feature.setProperty("marker-size", "small");
                feature.setProperty("marker-color", "#ccffcc");
                featureCollection.add(feature);
            }

            geoJson = null;
            try {
                geoJson = MAPPER.writer().writeValueAsString(featureCollection);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
            System.out.println(geoJson);
        }

        double baseDistance = edgePoints.calcDistance(Helper.DIST_PLANE);


        int virtEdgeId = mainEdges + virtualEdges.size();

        // edges between base and snapped point
        VirtualEdgeIState baseEdge = null;          // Всегда ребро из базовой точки (C) в проекцию (B)
        VirtualEdgeIState baseReverseEdge = null;   // Обратное ребро из проекции (B) в базовую точку (C)

        boolean isBothNodeVirtual = prevNodeId >= mainNodes && nodeId >= mainNodes;

        // Если количество полос польше 2, то закрываем левые повороты бесконечностью, иначе добавляем ширину дороги
        boolean needCloseRoadCross = closestEdge.getAdditionalField() > 2;
        int roadWide = BASE_ROAD_WIDE;

        double baseEdgeDistance = baseDistance;
        double reverseEdgeDistance = baseDistance;

        // Если движение по базовому ребру CB разрешено в обе стороны
        if (isForward && isBackward) {
            // Если не обе вершины виртуальные, или обе вирнуальные, но нет данных по первой точке
            // -- нет данных по первой точке - на всякий случай backdoor для исключения
            if (!isBothNodeVirtual || lastQueryPoint == null) {
                // Если угол левый, то закрываем ребро BC (реверс)
                if (isConnerABCLeft) {
                    baseEdgeDistance = baseDistance;
                    reverseEdgeDistance = needCloseRoadCross ? 1000001 : baseDistance + roadWide;
                }
                // Если угол правый, то закрываем ребро CB (базовое)
                else {
                    baseEdgeDistance = needCloseRoadCross ? 1000002 : baseDistance + roadWide;
                    reverseEdgeDistance = baseDistance;
                }
            }
            // Если обе вершины виртуальные, то необходимо учитывать оба угла
            // Выезд с первой вершины и въезд на вторую
            else {
                // Поиск косого произведения
                vectorA = new GHPoint(pointB.getLon() - pointC.getLon(), pointB.getLat() - pointC.getLat());
                vectorB = new GHPoint(lastQueryPoint.getLon() - pointC.getLon(), lastQueryPoint.getLat() - pointC.getLat());
                fiberBundle = vectorA.getLon() * vectorB.getLat() - vectorB.getLon() * vectorA.getLat();
                boolean isConner2Left = fiberBundle < 0;

                if (isConnerABCLeft && isConner2Left) {
                    baseEdgeDistance = needCloseRoadCross ? 1000003 : baseDistance + roadWide;
                    reverseEdgeDistance = needCloseRoadCross ? 1000003 : baseDistance + roadWide;
                } else if (isConnerABCLeft && !isConner2Left) {
                    baseEdgeDistance = baseDistance;
                    reverseEdgeDistance = needCloseRoadCross ? 1000004 : baseDistance + 2 * roadWide;
                } else if (!isConnerABCLeft && isConner2Left) {
                    baseEdgeDistance = needCloseRoadCross ? 1000005 : baseDistance + 2 * roadWide;
                    reverseEdgeDistance = baseDistance;
                } else if (!isConnerABCLeft && !isConner2Left) {
                    baseEdgeDistance = needCloseRoadCross ? 1000006 : baseDistance + roadWide;
                    reverseEdgeDistance = needCloseRoadCross ? 1000006 : baseDistance + roadWide;
                }
            }
        }
        // Движение по базовому ребру CB разрешено только в направлении CB
        else if (isForward) {
            baseEdgeDistance = baseDistance;
            reverseEdgeDistance = 1000007;
        }
        // Движение по базовому CB разрешено только в направлении BC
        else if (isBackward) {
            baseEdgeDistance = 1000008;
            reverseEdgeDistance = baseDistance;
        }


        baseEdge = new VirtualEdgeIState(virtEdgeId, nodeC, nodeB,
                baseEdgeDistance,
                encoder.setAccess(closestEdge.getFlags(), true, false), closestEdge.getName(), basePoints
        );
        virtualEdgeIdToBaseEdge.put(virtEdgeId, res.getClosestEdge());
        virtEdgeId++;
        baseReverseEdge = new VirtualEdgeIState(virtEdgeId, nodeB, nodeC,
                reverseEdgeDistance,
                encoder.setAccess(reverseFlags, true, false), closestEdge.getName(), reversePoints
        );
        virtualEdgeIdToBaseEdge.put(virtEdgeId, res.getClosestEdge());

        virtualEdges.add(baseEdge);
        virtualEdges.add(baseReverseEdge);

        if (SYSTEM_DEBUG) {
            System.out.println();
            if (virtualEdges.size() >= 8) {
                Set<Integer> nodes = new HashSet<Integer>();
                Map<Integer, Integer> idToNumber = new HashMap<Integer, Integer>();

                FeatureCollection featureCollection = new FeatureCollection();
                int i = 1;
                for (EdgeIteratorState virtualEdge : virtualEdges) {
                    if (nodes.add(virtualEdge.getBaseNode())) {
                        Feature feature = new Feature();
                        feature.setGeometry(
                                new Point(
                                        debugPointInfo.get(virtualEdge.getBaseNode()).getLon(),
                                        debugPointInfo.get(virtualEdge.getBaseNode()).getLat()
                                )
                        );
                        feature.setProperty("nodeId", virtualEdge.getBaseNode());
                        idToNumber.put(virtualEdge.getBaseNode(), i);
                        feature.setProperty("marker-symbol", i++);
                        if (virtualEdge.getBaseNode() >= mainNodes) {
                            feature.setProperty("marker-color", "#ff0000");
                        }
                        featureCollection.add(feature);
                    }
                }

                char c = 'a';
                for (GHPoint debugQueryPoint : debugQueryPoints) {
                    Feature feature = new Feature();
                    feature.setGeometry(
                            new Point(
                                    debugQueryPoint.getLon(),
                                    debugQueryPoint.getLat()
                            )
                    );
                    feature.setProperty("marker-symbol", "" + c++);
                    feature.setProperty("marker-color", "#00ff00");
                    featureCollection.add(feature);
                }

                ObjectMapper MAPPER = new ObjectMapper();
                String geoJson = null;
                try {
                    geoJson = MAPPER.writer().writeValueAsString(featureCollection);
                } catch (JsonProcessingException e) {
                    e.printStackTrace();
                }
                System.out.println(geoJson);
                for (EdgeIteratorState virtualEdge : virtualEdges) {
                    System.out.println("  " + idToNumber.get(virtualEdge.getBaseNode()) + " -> " + idToNumber.get(virtualEdge.getAdjNode()) + " (" + virtualEdge.getBaseNode() + " -> " + virtualEdge.getAdjNode() + ") "
                            + "distance = " + virtualEdge.getDistance()
                    );
                }
            }
        }

        lastQueryPoint = res.getQueryPoint();
    }

    @Override
    public int getNodes() {
        return virtualNodes.getSize() + mainNodes;
    }

    @Override
    public NodeAccess getNodeAccess() {
        return nodeAccess;
    }

    private final NodeAccess nodeAccess = new NodeAccess() {
        @Override
        public void ensureNode(int nodeId) {
            mainNodeAccess.ensureNode(nodeId);
        }

        @Override
        public boolean is3D() {
            return mainNodeAccess.is3D();
        }

        @Override
        public int getDimension() {
            return mainNodeAccess.getDimension();
        }

        @Override
        public double getLatitude(int nodeId) {
            if (isVirtualNode(nodeId))
                return virtualNodes.getLatitude(nodeId - mainNodes);
            return mainNodeAccess.getLatitude(nodeId);
        }

        @Override
        public double getLongitude(int nodeId) {
            if (isVirtualNode(nodeId))
                return virtualNodes.getLongitude(nodeId - mainNodes);
            return mainNodeAccess.getLongitude(nodeId);
        }

        @Override
        public double getElevation(int nodeId) {
            if (isVirtualNode(nodeId))
                return virtualNodes.getElevation(nodeId - mainNodes);
            return mainNodeAccess.getElevation(nodeId);
        }

        @Override
        public int getAdditionalNodeField(int nodeId) {
            if (isVirtualNode(nodeId))
                return 0;
            return mainNodeAccess.getAdditionalNodeField(nodeId);
        }

        @Override
        public void setNode(int nodeId, double lat, double lon) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public void setNode(int nodeId, double lat, double lon, double ele) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public void setAdditionalNodeField(int nodeId, int additionalValue) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public double getLat(int nodeId) {
            return getLatitude(nodeId);
        }

        @Override
        public double getLon(int nodeId) {
            return getLongitude(nodeId);
        }

        @Override
        public double getEle(int nodeId) {
            return getElevation(nodeId);
        }
    };

    @Override
    public BBox getBounds() {
        return mainGraph.getBounds();
    }

    @Override
    public EdgeIteratorState getEdgeProps(int origEdgeId, int adjNode) {
        if (!isVirtualEdge(origEdgeId))
            return mainGraph.getEdgeProps(origEdgeId, adjNode);

        int edgeId = origEdgeId - mainEdges;
        EdgeIteratorState eis = virtualEdges.get(edgeId);
        if (eis.getAdjNode() == adjNode || adjNode == Integer.MIN_VALUE)
            return eis;

        // find reverse edge via convention. see virtualEdges comment above
        if (edgeId % 2 == 0)
            edgeId++;
        else
            edgeId--;
        EdgeIteratorState eis2 = virtualEdges.get(edgeId);
        if (eis2.getAdjNode() == adjNode)
            return eis2;
        throw new IllegalStateException("Edge " + origEdgeId + " not found with adjNode:" + adjNode
                + ". found edges were:" + eis + ", " + eis2);
    }

    @Override
    public EdgeExplorer createEdgeExplorer(final EdgeFilter edgeFilter) {
        if (!isInitialized())
            throw new IllegalStateException("Call lookup before using this graph");

        // Iteration over virtual nodes needs to be thread safe if done from different explorer
        // so we need to create the mapping on EVERY call!
        // This needs to be a HashMap (and cannot be an array) as we also need to tweak edges for some mainNodes!
        // The more query points we have the more inefficient this map could be. Hmmh.
        final TIntObjectMap<VirtualEdgeIterator> node2EdgeMap
                = new TIntObjectHashMap<VirtualEdgeIterator>(queryResults.size() * 3);


        final EdgeExplorer mainExplorer = mainGraph.createEdgeExplorer(edgeFilter);
        final TIntHashSet towerNodesToChange = new TIntHashSet(queryResults.size());

        // 1. virtualEdges should also get fresh EdgeIterators on every createEdgeExplorer call!        
        for (int i = 0; i < queryResults.size(); i++) {
            VirtualEdgeIterator virtEdgeIter;

            // vselivanov fix
            // Use ALL virtual edges, where base node pint is query point
            int virtualNode = -1;
            for (EdgeIteratorState virtualEdge : virtualEdges) {
                QueryResult queryResult = queryResults.get(i);
                PointList basePoints = virtualEdge.fetchWayGeometry(1);
                // If start base point equals query point
                if (basePoints.getLat(0) == queryResult.getSnappedPoint().getLat() && basePoints.getLon(0) == queryResult.getSnappedPoint().getLon()) {
                    virtEdgeIter = node2EdgeMap.get(virtualEdge.getBaseNode());
                    if (virtEdgeIter == null) {
                        virtEdgeIter = new VirtualEdgeIterator(virtualEdges.size());
                    }
                    virtEdgeIter.add(virtualEdge);
                    node2EdgeMap.put(virtualEdge.getBaseNode(), virtEdgeIter);
                }

                PointList adjPoints = virtualEdge.fetchWayGeometry(2);
                if (adjPoints.getLat(adjPoints.size() - 1) == queryResult.getSnappedPoint().getLat() && adjPoints.getLon(adjPoints.size() - 1) == queryResult.getSnappedPoint().getLon()) {
                    virtEdgeIter = node2EdgeMap.get(virtualEdge.getBaseNode());
                    if (virtEdgeIter == null) {
                        virtEdgeIter = new VirtualEdgeIterator(virtualEdges.size());
                    }
                    virtEdgeIter.add(virtualEdge);

                    if (edgeFilter.accept(virtualEdge)) {
                        node2EdgeMap.put(virtualEdge.getBaseNode(), virtEdgeIter);
                    }
                }
            }


        }

        // 2. the connected tower nodes from mainGraph need fresh EdgeIterators with possible fakes
        // where 'fresh' means independent of previous call and respecting the edgeFilter
        // -> setup fake iterators of detected tower nodes (virtual edges are already added)
        towerNodesToChange.forEach(new TIntProcedure() {
            @Override
            public boolean execute(int value) {
                fillVirtualEdges(node2EdgeMap, value, mainExplorer);
                return true;
            }
        });

        return new EdgeExplorer() {
            @Override
            public EdgeIterator setBaseNode(int baseNode) {
                VirtualEdgeIterator iter = node2EdgeMap.get(baseNode);
                if (iter != null) {
                    if (baseNode < mainNodes) {
                        EdgeIterator baseIter = mainExplorer.setBaseNode(baseNode);
                        while (baseIter.next()) {
                            iter.add(new VirtualEdgeIState(
                                    baseIter.getEdge(),
                                    baseIter.getBaseNode(),
                                    baseIter.getAdjNode(),
                                    baseIter.getDistance(),
                                    baseIter.getFlags(),
                                    baseIter.getName(),
                                    baseIter.fetchWayGeometry(3)
                            ));
                            iter.add(baseIter);
                        }
                    }
                    return iter.reset();
                }


                return mainExplorer.setBaseNode(baseNode);
            }
        };
    }

    /**
     * Creates a fake edge iterator pointing to multiple edge states.
     */
    private void addVirtualEdges(TIntObjectMap<VirtualEdgeIterator> node2EdgeMap, EdgeFilter filter, EdgeIteratorState state,
                                 int node, int virtNode) {
        VirtualEdgeIterator existingIter = node2EdgeMap.get(node);
        if (existingIter == null) {
            existingIter = new VirtualEdgeIterator(10);
            node2EdgeMap.put(node, existingIter);
        }
//        EdgeIteratorState edge = state;
//        EdgeIteratorState edge = base
//                ? virtualEdges.get(virtNode * 4 + VE_BASE)
//                : virtualEdges.get(virtNode * 4 + VE_ADJ_REV);

//        if (filter.accept(state))
        existingIter.add(state);
    }

    void fillVirtualEdges(TIntObjectMap<VirtualEdgeIterator> node2Edge, int towerNode, EdgeExplorer mainExpl) {
        if (isVirtualNode(towerNode))
            throw new IllegalStateException("Node should not be virtual:" + towerNode + ", " + node2Edge);

        VirtualEdgeIterator vIter = node2Edge.get(towerNode);
        TIntArrayList ignoreEdges = new TIntArrayList(vIter.count() * 2);

//      while (vIter.next()) {
//            EdgeIteratorState edge = queryResults.get(vIter.getAdjNode() - mainNodes).getClosestEdge();
//            ignoreEdges.add(edge.getEdge());
//        }
        vIter.reset();
        EdgeIterator iter = mainExpl.setBaseNode(towerNode);
        while (iter.next()) {
            if (!ignoreEdges.contains(iter.getEdge()))
                vIter.add(iter.detach(false));
        }
    }

    private boolean isInitialized() {
        return queryResults != null;
    }

    @Override
    public EdgeExplorer createEdgeExplorer() {
        return createEdgeExplorer(EdgeFilter.ALL_EDGES);
    }

    @Override
    public AllEdgesIterator getAllEdges() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public EdgeIteratorState edge(int a, int b) {
        throw exc();
    }

    public EdgeIteratorState edge(int a, int b, double distance, int flags) {
        throw exc();
    }

    @Override
    public EdgeIteratorState edge(int a, int b, double distance, boolean bothDirections) {
        throw exc();
    }

    @Override
    public Graph copyTo(Graph g) {
        throw exc();
    }

    @Override
    public GraphExtension getExtension() {
        return wrappedExtension;
    }

    private UnsupportedOperationException exc() {
        return new UnsupportedOperationException("QueryGraph cannot be modified.");
    }
}
