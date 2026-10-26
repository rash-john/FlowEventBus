# Keep @Subscribe methods (found by reflection) and the annotation itself.
-keepattributes *Annotation*
-keep @interface com.floweventbus.Subscribe
-keepclassmembers class * {
    @com.floweventbus.Subscribe <methods>;
}
-keep enum com.floweventbus.ThreadMode { *; }
