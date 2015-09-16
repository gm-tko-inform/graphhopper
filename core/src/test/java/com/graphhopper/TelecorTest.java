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

//        String city = "nn";
        String city = "kzn";

        graphHopper.setGraphHopperLocation("gh-problem")
                .setInMemory()
                .setEncodingManager(
                        new EncodingManager("gv|turnCosts=true")
                )
                .setOSMFile("D:\\Projects\\graphhopper\\data\\map_"+city+".pbf")
                .setGraphHopperLocation("D:\\Projects\\graphhopper\\data\\cache_"+ city)
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

        // Каспийская (без количества полос - одно ребро лево-лево)
//        ghPoints.add(new GHPoint(55.81173008219259, 49.20269429683685));
//        ghPoints.add(new GHPoint(55.811431661210115, 49.20191109180450));

        // Каспийская (без количества полос - одно ребро лево-право)
//        ghPoints.add(new GHPoint(55.81173008219259, 49.20269429683685));
//        ghPoints.add(new GHPoint(55.81156730739489, 49.20174211263656));

        // Каспийская (без количества полос - одно ребро право-право)
//        ghPoints.add(new GHPoint(55.81193204256935,  49.20247972011566));
//        ghPoints.add(new GHPoint(55.81193204256935, 49.20174211263656));

        // Каспийская (без количества полос - одно ребро право-лево)
//        ghPoints.add(new GHPoint(55.81193204256935,  49.20247972011566));
//        ghPoints.add(new GHPoint(55.811431661210115, 49.20191109180450));




        // Коновалова (без полос, с лева на лево)
//        ghPoints.add(new GHPoint(55.81338793487428, 49.201626777648926));
//        ghPoints.add(new GHPoint(55.815365208637346, 49.2003071308136));

        // Коновалова (без полос наоборот)
        ghPoints.add(new GHPoint(55.815365208637346, 49.2003071308136));
        ghPoints.add(new GHPoint(55.81338793487428, 49.201626777648926));


        // Арбузова на оборонную
//        ghPoints.add(new GHPoint(55.81244296750008, 49.1950660943985));
//        ghPoints.add(new GHPoint(55.812381174575904, 49.19336557388306));

        // Губкина (на одной стороне)
//        ghPoints.add(new GHPoint(55.80913163282477, 49.199631214141846));
//        ghPoints.add(new GHPoint(55.80874576940273, 49.19776439666747));

        // Губкина (с одностороннего в обратку на двустороннее)
//        ghPoints.add(new GHPoint(55.808127777047204, 49.1954630613327));
//        ghPoints.add(new GHPoint(55.80916177824352, 49.1983437538147));

        // Губкина (с одностороннего в обратку на двустороннее)
//        ghPoints.add(new GHPoint(55.808127777047204, 49.1954630613327));
//        ghPoints.add(new GHPoint(55.80819711326353, 49.19562935829162));


        // Губкина с одностороннего через царицино
//        ghPoints.add(new GHPoint(55.807895650554535,  49.195908308029175));
//        ghPoints.add(new GHPoint(55.80916177824352, 49.1983437538147));

        // Беломорская, одностороннее движение в обратку
//        ghPoints.add(new GHPoint(55.86436705806156,  49.0846985578537));
//        ghPoints.add(new GHPoint(55.8642135342982, 49.08549785614014));

        // Беломорская, одностороннее движение с поворотом налево (на одном ребре)
//        ghPoints.add(new GHPoint(55.86436705806156,  49.0846985578537));
//        ghPoints.add(new GHPoint(55.86418945208418, 49.08443570137024));

        // Беломорская, одностороннее движение с поворотом налево (разные рёбра)
//        ghPoints.add(new GHPoint(55.86436705806156,  49.0846985578537));
//        ghPoints.add(new GHPoint(55.86500221855593, 49.079438745975494));

        // Беломорская, одностороннее движение с обеими точками по левую сторону
//        ghPoints.add(new GHPoint(55.86419246236176,  49.084296226501465));
//        ghPoints.add(new GHPoint(55.86500221855593, 49.079438745975494));

        // Беломорская, одностороннее движение с обеими точками по правую сторону
//        ghPoints.add(new GHPoint(55.8643444810755,  49.084650278091424));
//        ghPoints.add(new GHPoint(55.864710226632674, 49.08297657966614));











        // Нагорный - утренняя
//        ghPoints.add(new GHPoint(55.84831301444013, 49.22209739685058));
//        ghPoints.add(new GHPoint(55.85387788266578, 49.24522876739502));

//        Тест Москва
//        ghPoints.add(new GHPoint(55.8161890377329, 37.38191619515419));
//        ghPoints.add(new GHPoint(55.773824, 37.490362));

        // Нижний
        // Корейская 20, Гжатскяа, 4
//        ghPoints.add(new GHPoint(56.274996219864335, 43.990872502326965));
//        ghPoints.add(new GHPoint(56.275514451826986, 43.991392850875854));



        // Тест на одном ребре (слева)
//        ghPoints.add(new GHPoint(56.2623, 43.9770));
//        ghPoints.add(new GHPoint(56.26281872653696, 43.97745877504349));

        // Тест на одном ребре (справа с объездом)
//        ghPoints.add(new GHPoint(56.2623, 43.9770));
//        ghPoints.add(new GHPoint(56.26282319550621, 43.97770017385483));

        // Произвольный
//        ghPoints.add(new GHPoint(56.29425245558566, 44.00144577026367));
//        ghPoints.add(new GHPoint(56.29368089320706, 44.02517795562744));






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
        feature.setProperty("stroke", "#0000ff");
        feature.setProperty("stroke-width", 5);
        feature.setProperty("stroke-opacity", 0.4);
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
        feature.setProperty("marker-color", "#0101fc");
        feature.setProperty("marker-size", "small");
        feature.setProperty("marker-symbol", "1");
        feature.setGeometry(
                new Point(ghPoints.get(0).getLon(), ghPoints.get(0).getLat())
        );
        featureCollection.add(feature);

        feature = new Feature();
        feature.setGeometry(
                new Point(ghPoints.get(1).getLon(), ghPoints.get(1).getLat())
        );
        feature.setProperty("route", "end");
        feature.setProperty("marker-color", "#0101fc");
        feature.setProperty("marker-size", "small");
        feature.setProperty("marker-symbol", "2");
        featureCollection.add(feature);

        String geoJson = toJsonString(featureCollection);
        System.out.println(geoJson);
        System.out.println("distance="+ghResponse.getDistance());

    }
}
