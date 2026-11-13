package mcport.macro;

/**
 * Parts that one build has and another does not. The published build leaves the ignore list out (it started from
 * another mod's code): its classes are simply not in that jar, and everything that would reach them asks here first.
 */
public final class Features {
   /** mcport.ignore.Ignore and its two mixins are in this jar. */
   public static final boolean IGNORE = present("mcport.ignore.Ignore");

   private Features() {
   }

   private static boolean present(String name) {
      try {
         Class.forName(name, false, Features.class.getClassLoader());
         return true;
      } catch (ClassNotFoundException e) {
         return false;
      }
   }
}
