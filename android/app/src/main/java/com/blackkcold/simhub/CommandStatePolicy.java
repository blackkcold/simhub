package com.blackkcold.simhub;

/** Node-side monotonic command states. Terminal results cannot be overwritten. */
final class CommandStatePolicy {
    private CommandStatePolicy(){}

    static boolean accepts(String current,String next){
        if(current==null||next==null||"claimed".equals(next))return false;
        if(current.equals(next))return true;
        return switch(current){
            case "claimed" -> switch(next){
                case "submitted","sent","delivered","succeeded","failed","rejected","expired","outcome_unknown" -> true;
                default -> false;
            };
            case "submitted" -> switch(next){
                case "sent","delivered","succeeded","failed","outcome_unknown" -> true;
                default -> false;
            };
            case "outcome_unknown" -> switch(next){
                case "sent","delivered","succeeded","failed","rejected","expired" -> true;
                default -> false;
            };
            case "sent" -> "delivered".equals(next)||"failed".equals(next);
            default -> false;
        };
    }
}
