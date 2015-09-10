package com.graphhopper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.GHPoint;
import org.geojson.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * <p/>
 *
 * @author vselivanov
 */
public class TelecorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();


    public static String toJsonString(Object aObject) {
        try {
            return MAPPER.writer().writeValueAsString(aObject);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }


    public static void main(String[] args) throws IOException {
        GraphHopper graphHopper;

//        CmdArgs cmdArgs = CmdArgs.readFromConfig(null, "graphhopper.config");


        graphHopper = new GraphHopper();


        graphHopper.setGraphHopperLocation("gh-problem")
                .setInMemory()
                .setEncodingManager(
                        new EncodingManager("gv|turnCosts=true")
                )
                .setOSMFile("D:\\Projects\\graphhopper\\data\\map_nn.pbf")
                .setGraphHopperLocation("D:\\Projects\\graphhopper\\data\\cache")
                .setCHEnable(false)
                .forServer();

        graphHopper.importOrLoad();

//
//        <rtept lat = "55.840353" lon = "49.199553" / >
//        <rtept lat = "55.841541" lon = "49.200768" / >
//
//        <rtept lat = "55.839495" lon = "49.198635" / >
//        <rtept lat = "55.839883" lon = "49.199058" / >

        List<GHPoint> ghPoints = new ArrayList<GHPoint>();
//        ghPoints.add(new GHPoint(55.840353, 49.199553));
//        ghPoints.add(new GHPoint(55.841541, 49.200768));
//        ghPoints.add(new GHPoint(55.83949, 49.198635));
//        ghPoints.add(new GHPoint(55.839883, 49.199058));

//        Губкина (на другую сторону)
//        ghPoints.add(new GHPoint(55.80913163282477, 49.199631214141846));
//        ghPoints.add(new GHPoint(55.8089567889356, 49.19753909111023));

        // Губкина (на одной стороне)
//        ghPoints.add(new GHPoint(55.80913163282477, 49.199631214141846));
//        ghPoints.add(new GHPoint(55.80874576940273, 49.19776439666747));

        // Губкина (с одностороннего в обратку на двустороннее)
//        ghPoints.add(new GHPoint(55.808127777047204, 49.1954630613327));
//        ghPoints.add(new GHPoint(55.80916177824352, 49.1983437538147));

        // Нагорный - утренняя
//        ghPoints.add(new GHPoint(55.84831301444013, 49.22209739685058));
//        ghPoints.add(new GHPoint(55.85387788266578, 49.24522876739502));

//        Тест Москва
//        ghPoints.add(new GHPoint(55.8161890377329, 37.38191619515419));
//        ghPoints.add(new GHPoint(55.773824, 37.490362));

        // Нижний
        // Корейская 20, Гжатскяа, 4
        ghPoints.add(new GHPoint(56.2750766356292, 43.9907866716385));
        ghPoints.add(new GHPoint(56.2753029902075, 43.9913884084672));




        GHRequest request = new GHRequest(
                ghPoints
        );

//        GHRequest request = new GHRequest(
//                55.840669, 49.203504,
//                55.8412, 49.200432
//        );
        GHResponse ghResponse = graphHopper.route(request);

        PointList pointList = ghResponse.getPoints();

        FeatureCollection featureCollection = new FeatureCollection();

        Feature feature = new Feature();
        feature.setProperty("route", "line");
        MultiLineString multiLineString = new MultiLineString();
        feature.setGeometry(multiLineString);
        featureCollection.add(feature);

        List<LngLatAlt> route = new ArrayList<LngLatAlt>();
        for (int i = 0; i < pointList.size(); i++) {
            route.add(new LngLatAlt(pointList.getLon(i), pointList.getLat(i)));
        }
        multiLineString.add(route);

        feature = new Feature();
        feature.setProperty("route", "start");
        feature.setGeometry(
                new Point(ghPoints.get(0).getLon(), ghPoints.get(0).getLat())
        );
        featureCollection.add(feature);

        feature = new Feature();
        feature.setGeometry(
                new Point(ghPoints.get(1).getLon(), ghPoints.get(1).getLat())
        );
        feature.setProperty("route", "end");
        feature.setProperty("marker-symbol", "square");
        featureCollection.add(feature);

        String geoJson = toJsonString(featureCollection);
        System.out.println(geoJson);
        System.out.println("distance="+ghResponse.getDistance());

    }
}
