package com.lingluren.yikatong.terminal;
/** One stable decision per presentation; 1.5 seconds without a face rearms. */
public final class TerminalAttemptGate {
    private boolean consumed;
    private long absentSince=-1,candidateSince=-1,lastSample=-1,candidateUser;
    private int samples;
    private long consumedUser,consumedAt;
    private String candidateFailure="";
    public boolean face(long now){absentSince=-1;if(consumed)return false;consumed=true;return true;}
    public boolean observing(){absentSince=-1;clearCandidate();return !consumed;}
    private void clearCandidate(){candidateSince=-1;lastSample=-1;samples=0;candidateFailure="";candidateUser=0;}
    public boolean decision(long user,String failure,long now){
        absentSince=-1;
        // A stable, different enrolled identity can start the next presentation.
        // Unknown faces never automatically rearm and create repeated denial events.
        if(consumed&&(user<=0||!failure.isEmpty()||user==consumedUser||now-consumedAt<1200)){clearCandidate();return false;}
        if(candidateSince<0||user!=candidateUser||!failure.equals(candidateFailure)||now-lastSample>1500){
            candidateSince=now;samples=0;candidateUser=user;candidateFailure=failure;
        }
        lastSample=now;samples++;
        boolean matched=user>0&&failure.isEmpty();
        if(samples<(matched?2:3)||now-candidateSince<(matched?200:600))return false;
        consumed=true;consumedUser=user;consumedAt=now;clearCandidate();return true;
    }
    public void absent(long now){clearCandidate();if(absentSince<0)absentSince=now;if(now-absentSince>=1500)consumed=false;}
    public void reset(){consumed=false;consumedUser=0;consumedAt=0;absentSince=-1;clearCandidate();}
}
