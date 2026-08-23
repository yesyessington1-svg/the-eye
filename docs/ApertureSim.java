public class Sim {

  /** feed a flat wall at forwardM with an opening of gapM centred at offsetM */
  static void wall(ApertureScan a, float forwardM, float gapM, float offsetM) {
    for (float lat = -6f; lat <= 6f; lat += 0.01f) {
      if (gapM > 0 && lat > offsetM - gapM / 2f && lat < offsetM + gapM / 2f) {
        // through the opening the beam carries on to the far wall of the next room
        for (float h = -0.9f; h <= 0.1f; h += 0.25f) {
          float far = forwardM + 3.5f;
          a.add(lat, h, (float) Math.sqrt(lat * lat + far * far));
        }
        continue;
      }
      for (float h = -0.9f; h <= 0.1f; h += 0.25f) {
        a.add(lat, h, (float) Math.sqrt(lat * lat + forwardM * forwardM));
      }
    }
  }

  /** an open room: distant walls all round, nothing to route past */
  static void openRoom(ApertureScan a) {
    for (float lat = -8f; lat <= 8f; lat += 0.01f) {
      for (float h = -0.9f; h <= 0.1f; h += 0.25f) {
        a.add(lat, h, (float) Math.sqrt(lat * lat + 7f * 7f));
      }
    }
  }

  static void run(String name, float forward, float gap, float offset) {
    ApertureScan a = new ApertureScan();
    ApertureScan.Gap g = null;
    // five frames, because the debounce needs three and the median needs a history
    for (int f = 0; f < 6; f++) {
      a.begin();
      if (forward > 0) {
        wall(a, forward, gap, offset);
      } else if (forward == 0) {
        openRoom(a);
      }
      g = a.finish();
    }
    System.out.printf(
        "%-34s -> %-8s w=%.2fm bear=%+5.1f barrier=%.2f cov=%.0f%% edge=%b%n    \"%s\"%n",
        name, g.fit, g.widthM, g.bearingDeg, g.barrierM, g.coverage * 100, g.openEdge,
        ApertureScan.describe(g));
  }

  /**
   * The phone tilted down, which is how you actually hold it when hunting for clutter on the
   * floor - and the case that broke coverage on 23 Aug. Almost every pixel lands on the floor,
   * below the body slab. Before the fix these frames scored ~0% coverage and the fan called itself
   * blind while staring at a floor it could see perfectly.
   *
   * @param floorBelow how far the floor sits below the camera, metres (forehead height ~1.35)
   * @param obstAt an obstacle this far forward, 0 for an empty floor
   */
  static void floorView(ApertureScan a, float floorBelow, float obstAt, float obstWidth,
      float obstHeight) {
    for (float lat = -3f; lat <= 3f; lat += 0.02f) {
      for (float fwd = 1.2f; fwd <= 6f; fwd += 0.05f) {
        // an object standing on the floor occludes the floor behind it. that occlusion is the
        // whole reason visible floor counts as proof of free space
        if (obstAt > 0 && Math.abs(lat) <= obstWidth / 2f && fwd > obstAt) {
          continue;
        }
        a.add(lat, -floorBelow, (float) Math.sqrt(lat * lat + fwd * fwd));
      }
    }
    if (obstAt > 0) {
      for (float lat = -obstWidth / 2f; lat <= obstWidth / 2f; lat += 0.005f) {
        for (float h = -floorBelow; h <= -floorBelow + obstHeight; h += 0.01f) {
          a.add(lat, h, (float) Math.sqrt(lat * lat + obstAt * obstAt));
        }
      }
    }
  }

  static void runFloor(String name, float floorBelow, float obstAt, float obstWidth,
      float obstHeight) {
    ApertureScan a = new ApertureScan();
    // exactly what GuardianCorridor computes: 15cm of clearance above the measured floor, never
    // higher than -1.15 and never lower than -1.60
    float bottom = Math.max(-1.60f, Math.min(-1.15f, -floorBelow + 0.15f));
    a.setSlabBottom(bottom);
    ApertureScan.Gap g = null;
    for (int f = 0; f < 6; f++) {
      a.begin();
      floorView(a, floorBelow, obstAt, obstWidth, obstHeight);
      g = a.finish();
    }
    System.out.printf(
        "%-34s -> %-8s w=%.2fm bear=%+5.1f barrier=%.2f cov=%.0f%% edge=%b%n    \"%s\"%n",
        name, g.fit, g.widthM, g.bearingDeg, g.barrierM, g.coverage * 100, g.openEdge,
        ApertureScan.describe(g));
  }

  public static void main(String[] args) {
    run("open room, walls at 7m", 0, 0, 0);
    run("no depth at all", -1, 0, 0);
    run("door 0.80m @2.5m centred", 2.5f, 0.80f, 0f);
    run("door 0.80m @1.5m centred", 1.5f, 0.80f, 0f);
    run("door 0.90m @2.5m, 0.6m left", 2.5f, 0.90f, -0.6f);
    run("door 0.55m @2.0m (squeeze)", 2.0f, 0.55f, 0f);
    run("door 0.30m @2.0m (no fit)", 2.0f, 0.30f, 0f);
    run("solid wall @1.5m", 1.5f, 0f, 0f);
    run("solid wall @3.5m", 3.5f, 0f, 0f);
    run("door 1.20m @3.0m centred", 3.0f, 1.20f, 0f);
    run("door 0.70m @2.0m, 0.5m right", 2.0f, 0.70f, 0.5f);
    run("door 0.45m @1.5m (tight)", 1.5f, 0.45f, 0f);
    run("door 0.62m @2.0m (walk edge)", 2.0f, 0.62f, 0f);

    System.out.println();
    System.out.println("-- phone tilted down, frame is mostly floor --");
    runFloor("empty floor, nothing on it", 1.35f, 0f, 0f, 0f);
    runFloor("bottle 30cm tall @1.5m", 1.35f, 1.5f, 0.30f, 0.30f);
    runFloor("suitcase 55cm tall @2.0m", 1.35f, 2.0f, 0.45f, 0.55f);
    runFloor("couch 80cm tall @2.5m", 1.35f, 2.5f, 1.80f, 0.80f);
    runFloor("bottle 30cm @1.0m (close)", 1.35f, 1.0f, 0.30f, 0.30f);
    runFloor("low camera 1.10m, bottle @1.5m", 1.10f, 1.5f, 0.30f, 0.30f);
  }
}
