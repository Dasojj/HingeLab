package dev.duohome.hingelab;

/** Pure decisions: missing/stale angle must never cause a display transition. */
public final class AdaptiveDisplayPolicy {
    public static boolean shouldEnter(int base, Float angle, long ageMs) {
        return shouldEnter(base,angle,ageMs,false);
    }
    public static boolean shouldEnter(int base, Float angle, long ageMs, boolean early) {
        return base==1 || base==2 || (base==3 && fresh(angle,ageMs) && angle<=(early ? 160f : 150f));
    }
    public static boolean shouldRelease(int base, Float angle, long ageMs) {
        return base==0 || (base==3 && fresh(angle,ageMs) && angle>=178f);
    }
    private static boolean fresh(Float angle, long ageMs) {
        return angle!=null && Float.isFinite(angle) && angle>=0 && angle<=180 && ageMs>=0 && ageMs<=500;
    }
}
