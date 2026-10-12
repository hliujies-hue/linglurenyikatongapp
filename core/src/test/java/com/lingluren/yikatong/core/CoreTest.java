package com.lingluren.yikatong.core;
public final class CoreTest {
 static void check(boolean b, String why) { if (!b) throw new AssertionError(why); }
 public static void main(String[] args) {
  long day = 86400000L;
  check(!EventPolicy.mayDelete(365*day,0,false),"unacknowledged records must survive indefinitely");
  check(!EventPolicy.mayDelete(6*day,0,true),"retain records younger than seven days");
  check(EventPolicy.mayDelete(7*day,0,true),"delete only acknowledged aged records");
  check(!EventPolicy.mayDelete(1,2,true),"clock rollback must not delete records");
  check(!EventPolicy.mayPresentPass("new","old",100,true,true),"late response must not pass next visitor");
  check(!EventPolicy.mayPresentPass("id","id",8001,true,true),"expired response must not show green");
  check(!EventPolicy.mayPresentPass("id","id",10,false,true),"background must not announce pass");
  check(!EventPolicy.mayPresentPass("id","id",10,true,false),"local match is not permission");
  check(EventPolicy.mayPresentPass("id","id",500,true,true),"current server-approved event");
  check("异常003".equals(EventPolicy.defaultText("LIMIT_EXCEEDED")),"quota message");
  System.out.println("PASS: 10 event-retention and presentation checks");
 }
}
