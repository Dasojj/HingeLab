package dev.duohome.hingelab;

public final class TransitionHoldPolicy {
    private static boolean fresh(Float angle,long age) {
        return angle!=null && Float.isFinite(angle) && angle>=0 && angle<=180 && age>=0 && age<=500;
    }
    public static boolean closing(boolean active,Float angle,long age) { return active && fresh(angle,age) && angle<=15; }
    public static boolean rearm(Float angle,long age) { return fresh(angle,age) && angle>30; }
}
