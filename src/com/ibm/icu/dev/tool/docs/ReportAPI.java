/**
*******************************************************************************
* Copyright (C) 2004-2005, International Business Machines Corporation and    *
* others. All Rights Reserved.                                                *
*******************************************************************************
*/

/**
 * Compare two API files (generated by GatherAPIData) and generate a report
 * on the differences.
 *
 * Sample invocation:
 * java -old: icu4j28.api.zip -new: icu4j30.api -html -out: icu4j_compare_28_30.html
 *
 * TODO:
 * - make 'changed apis' smarter - detect method parameter or return type change
 *   for this, the sequential search through methods ordered by signature won't do.
 *     We need to gather all added and removed overloads for a method, and then
 *     compare all added against all removed in order to identify this kind of
 *     change.
 */

package com.ibm.icu.dev.tool.docs;

import java.io.*;
import java.util.*;
import java.text.*;
import java.util.zip.*;

public class ReportAPI {
    APIData oldData;
    APIData newData;
    boolean html;
    String outputFile;

    TreeSet added;
    TreeSet removed;
    TreeSet promoted;
    TreeSet obsoleted;
    ArrayList changed;

    static final class APIData {
        private int version;
        private String name;
        private String base;
        private TreeSet set;

        static APIData read(BufferedReader br) {
            try {
                APIData data = new APIData();

                data.version = Integer.parseInt(APIInfo.readToken(br)); // version
                data.name = APIInfo.readToken(br);
                data.base = APIInfo.readToken(br); // base
                br.readLine();

                data.set = new TreeSet(APIInfo.defaultComparator());
                for (APIInfo info = new APIInfo(); info.read(br); info = new APIInfo()) {
                    data.set.add(info);
                }
                return data;
            }
            catch (IOException e) {
                RuntimeException re = new RuntimeException("error reading api data");
                re.initCause(e);
                throw re;
            }
        }

        static APIData read(String fileName) {
            try {
                InputStream is;
                if (fileName.endsWith(".zip")) {
                    ZipFile zf = new ZipFile(fileName);
                    Enumeration entryEnum = zf.entries();
                    if (entryEnum.hasMoreElements()) {
                        ZipEntry entry = (ZipEntry)entryEnum.nextElement();
                        is = zf.getInputStream(entry);
                        // we only handle one!!!
                    } else {
                        throw new IOException("zip file is empty");
                    }
                } else {
                    File f = new File(fileName);
                    is = new FileInputStream(f);
                    if (fileName.endsWith(".gz")) {
                        is = new GZIPInputStream(is);
                    }
                }
                InputStreamReader isr = new InputStreamReader(is);
                return read(new BufferedReader(isr));
            }
            catch (IOException e) {
                RuntimeException re = new RuntimeException("error getting info stream: " + fileName);
                re.initCause(e);
                throw re;
            }
        }
    }

    static final class DeltaInfo extends APIInfo {
        APIInfo added;
        APIInfo removed;

        DeltaInfo(APIInfo added, APIInfo removed) {
            this.added = added;
            this.removed = removed;
        }

        public int getVal(int typ) {
            return added.getVal(typ);
        }

        public String get(int typ, boolean brief) {
            return added.get(typ, brief);
        }

        public void print(PrintWriter pw, boolean detail, boolean html) {
            pw.print("    ");
            removed.print(pw, detail, html);
            if (html) {
                pw.println("</br>");
            } else {
                pw.println();
                pw.print("--> ");
            }
            added.print(pw, detail, html);
        }
    }

    public static void main(String[] args) {
        String oldFile = null;
        String newFile = null;
        String outFile = null;
        boolean html = false;

        for (int i = 0; i < args.length; ++i) {
            String arg = args[i];
            if (arg.equals("-old:")) {
                oldFile = args[++i];
            } else if (arg.equals("-new:")) {
                newFile = args[++i];
            } else if (arg.equals("-out:")) {
                outFile = args[++i];
            } else if (arg.equals("-html")) {
                html = true;
            }
        }

        new ReportAPI(oldFile, newFile).writeReport(outFile, html);
    }

    /*
      while the both are methods and the class and method names are the same, collect
      overloads.  when you hit a new method or class, compare the overloads
      looking for the same # of params and simple param changes.  ideally
      there are just a few.

      String oldA = null;
      String oldR = null;
      if (!a.isMethod()) {
      remove and continue
      }
      String am = a.getClassName() + "." + a.getName();
      String rm = r.getClassName() + "." + r.getName();
      int comp = am.compare(rm);
      if (comp == 0 && a.isMethod() && r.isMethod())

    */

    ReportAPI(String oldFile, String newFile) {
        oldData = APIData.read(oldFile);
        newData = APIData.read(newFile);

        removed = (TreeSet)oldData.set.clone();
        removed.removeAll(newData.set);

        added = (TreeSet)newData.set.clone();
        added.removeAll(oldData.set);

        changed = new ArrayList();
        Iterator ai = added.iterator();
        Iterator ri = removed.iterator();
        Comparator c = APIInfo.changedComparator();

        ArrayList ams = new ArrayList();
        ArrayList rms = new ArrayList();
        PrintWriter outpw = new PrintWriter(System.out);

        APIInfo a = null, r = null;
        while (ai.hasNext() && ri.hasNext()) {
            if (a == null) a = (APIInfo)ai.next();
            if (r == null) r = (APIInfo)ri.next();

            String am = a.getClassName() + "." + a.getName();
            String rm = r.getClassName() + "." + r.getName();
            int comp = am.compareTo(rm);
            if (comp == 0 && a.isMethod() && r.isMethod()) { // collect overloads
                ams.add(a); a = null;
                rms.add(r); r = null;
                continue;
            }

            if (!ams.isEmpty()) {
                // simplest case first
                if (ams.size() == 1 && rms.size() == 1) {
                    changed.add(new DeltaInfo((APIInfo)ams.get(0), (APIInfo)rms.get(0)));
                } else {
                    // dang, what to do now?
                    // TODO: modify deltainfo to deal with lists of added and removed
                }
                ams.clear();
                rms.clear();
            }

            int result = c.compare(a, r);
            if (result < 0) {
                a = null;
            } else if (result > 0) {
                r = null;
            } else {
                changed.add(new DeltaInfo(a, r));
                a = null;
                r = null;
            }
        }

        // now clean up added and removed by cleaning out the changed members
        Iterator ci = changed.iterator();
        while (ci.hasNext()) {
            DeltaInfo di = (DeltaInfo)ci.next();
            added.remove(di.added);
            removed.remove(di.removed);
        }

        Set tempAdded = new HashSet();
        tempAdded.addAll(newData.set);
        tempAdded.removeAll(removed);
        TreeSet changedAdded = new TreeSet(APIInfo.defaultComparator());
        changedAdded.addAll(tempAdded);

        Set tempRemoved = new HashSet();
        tempRemoved.addAll(oldData.set);
        tempRemoved.removeAll(added);
        TreeSet changedRemoved = new TreeSet(APIInfo.defaultComparator());
        changedRemoved.addAll(tempRemoved);

        promoted = new TreeSet(APIInfo.defaultComparator());
        obsoleted = new TreeSet(APIInfo.defaultComparator());
        ai = changedAdded.iterator();
        ri = changedRemoved.iterator();
        a = r = null;
        while (ai.hasNext() && ri.hasNext()) {
            if (a == null) a = (APIInfo)ai.next();
            if (r == null) r = (APIInfo)ri.next();
            int result = c.compare(a, r);
            if (result < 0) {
                a = null;
            } else if (result > 0) {
                r = null;
            } else {
                int change = statusChange(a, r);
                if (change > 0) {
                    promoted.add(a);
                } else if (change < 0) {
                    obsoleted.add(a);
                }
                a = null;
                r = null;
            }
        }

        added = stripAndResort(added);
        removed = stripAndResort(removed);
        promoted = stripAndResort(promoted);
        obsoleted = stripAndResort(obsoleted);
    }

    private int statusChange(APIInfo lhs, APIInfo rhs) { // new. old
        for (int i = 0; i < APIInfo.NUM_TYPES; ++i) {
            if (lhs.get(i, true).equals(rhs.get(i, true)) == (i == APIInfo.STA)) {
                return 0;
            }
        }
        int lstatus = lhs.getVal(APIInfo.STA);
        if (lstatus == APIInfo.STA_OBSOLETE || lstatus == APIInfo.STA_DEPRECATED) {
            return -1;
        }
        return 1;
    }

    private boolean writeReport(String outFile, boolean html) {
        OutputStream os = System.out;
        if (outFile != null) {
            try {
                os = new FileOutputStream(outFile);
            }
            catch (FileNotFoundException e) {
                RuntimeException re = new RuntimeException(e.getMessage());
                re.initCause(e);
                throw re;
            }
        }

        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new BufferedWriter(new OutputStreamWriter(os, "UTF-8")));
        }
        catch (UnsupportedEncodingException e) {
            throw new InternalError(); // UTF-8 should always be supported
        }

        DateFormat fmt = new SimpleDateFormat("yyyy");
        String year = fmt.format(new Date());
        String title = "ICU4J API Comparison: " + oldData.name + " with " + newData.name;
        String info = "Contents generated by ReportAPI tool on " + new Date().toString();
        String copyright = "Copyright (C) " + year + ", International Business Machines Corporation, All Rights Reserved.";

        if (html) {
            pw.println("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\">");
            pw.println("<html>");
            pw.println("<head>");
            pw.println("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">");
            pw.println("<title>" + title + "</title>");
            pw.println("<!-- Copyright " + year + ", IBM, All Rights Reserved. -->");
            pw.println("</head>");
            pw.println("<body>");

            pw.println("<h1>" + title + "</h1>");

            pw.println();
            pw.println("<hr/>");
            pw.println("<h2>Removed from " + oldData.name +"</h2>");
            if (removed.size() > 0) {
                printResults(removed, pw, true, false);
            } else {
                pw.println("<p>(no API removed)</p>");
            }

            pw.println();
            pw.println("<hr/>");
            pw.println("<h2>Obsoleted in " + newData.name + "</h2>");
            if (obsoleted.size() > 0) {
                printResults(obsoleted, pw, true, false);
            } else {
                pw.println("<p>(no API obsoleted)</p>");
            }

            pw.println();
            pw.println("<hr/>");
            pw.println("<h2>Changed in " + newData.name + " (old, new)</h2>");
            if (changed.size() > 0) {
                printResults(changed, pw, true, true);
            } else {
                pw.println("<p>(no API changed)</p>");
            }

            pw.println();
            pw.println("<hr/>");
            pw.println("<h2>Promoted to stable in " + newData.name + "</h2>");
            if (promoted.size() > 0) {
                printResults(promoted, pw, true, false);
            } else {
                pw.println("<p>(no API promoted)</p>");
            }

            pw.println();
            pw.println("<hr/>");
            pw.println("<h2>Added in " + newData.name + "</h2>");
            if (added.size() > 0) {
                printResults(added, pw, true, false);
            } else {
                pw.println("<p>(no API added)</p>");
            }

            pw.println("<hr/>");
            pw.println("<p><i><font size=\"-1\">" + info + "<br/>" + copyright + "</font></i></p>");
            pw.println("</body>");
            pw.println("</html>");
        } else {
            pw.println(title);
            pw.println();
            pw.println();

            pw.println("=== Removed from " + oldData.name + " ===");
            if (removed.size() > 0) {
                printResults(removed, pw, false, false);
            } else {
                pw.println("(no API removed)");
            }

            pw.println();
            pw.println();
            pw.println("=== Obsoleted in " + newData.name + " ===");
            if (obsoleted.size() > 0) {
                printResults(obsoleted, pw, false, false);
            } else {
                pw.println("(no API obsoleted)");
            }

            pw.println();
            pw.println();
            pw.println("=== Changed in " + newData.name + " (old, new) ===");
            if (changed.size() > 0) {
                printResults(changed, pw, false, true);
            } else {
                pw.println("(no API changed)");
            }

            pw.println();
            pw.println();
            pw.println("=== Promoted to stable in " + newData.name + " ===");
            if (promoted.size() > 0) {
                printResults(promoted, pw, false, false);
            } else {
                pw.println("(no API promoted)");
            }

            pw.println();
            pw.println();
            pw.println("=== Added in " + newData.name + " ===");
            if (added.size() > 0) {
                printResults(added, pw, false, false);
            } else {
                pw.println("(no API added)");
            }

            pw.println();
            pw.println("================");
            pw.println(info);
            pw.println(copyright);
        }
        pw.close();

        return false;
    }

    private static void printResults(Collection c, PrintWriter pw, boolean html, boolean isChangedAPIs) {
        Iterator iter = c.iterator();
        String pack = null;
        String clas = null;
        while (iter.hasNext()) {
            APIInfo info = (APIInfo)iter.next();

            String packageName = info.getPackageName();
            if (!packageName.equals(pack)) {
                if (html) {
                    if (clas != null) {
                        pw.println("</ul>");
                    }
                    if (pack != null) {
                        pw.println("</ul>");
                    }
                    pw.println();
                    pw.println("<h3>Package " + packageName + "</h3>");
                    pw.print("<ul>");
                } else {
                    if (pack != null) {
                        pw.println();
                    }
                    pw.println();
                    pw.println("Package " + packageName + ":");
                }
                pw.println();

                pack = packageName;
                clas = null;
            }

            if (!info.isClass()) {
                String className = info.getClassName();
                if (!className.equals(clas)) {
                    if (html) {
                        if (clas != null) {
                            pw.println("</ul>");
                        }
                        pw.println(className);
                        pw.println("<ul>");
                    } else {
                        pw.println(className);
                    }
                    clas = className;
                }
                //                 pw.print("    ");
            }

            if (html) {
                pw.print("<li>");
                //                 if (info instanceof DeltaInfo) {
                //                     DeltaInfo dinfo = (DeltaInfo)info;
                //                     dinfo.removed.print(pw, isChangedAPIs, html);
                //                     pw.println("</br>");
                //                     dinfo.added.print(pw, isChangedAPIs, html);
                //                 } else {
                info.print(pw, isChangedAPIs, html);
                //                 }
                pw.println("</li>");
            } else {
                //                 if (info instanceof DeltaInfo) {
                //                     DeltaInfo dinfo = (DeltaInfo)info;
                //                     dinfo.removed.println(pw, isChangedAPIs, html);
                //                  pw.print("    --> ");
                //                     dinfo.added.println(pw, isChangedAPIs, html);
                //                 } else {
                info.println(pw, isChangedAPIs, html);
                //                 }
            }
        }

        if (html) {
            if (clas != null) {
                pw.println("</ul>");
            }
            if (pack != null) {
                pw.println("</ul>");
            }
        }
        pw.println();
    }

    private static TreeSet stripAndResort(TreeSet t) {
        stripClassInfo(t);
        TreeSet r = new TreeSet(APIInfo.classFirstComparator());
        r.addAll(t);
        return r;
    }

    private static void stripClassInfo(Collection c) {
        // c is sorted with class info first
        Iterator iter = c.iterator();
        String cname = null;
        while (iter.hasNext()) {
            APIInfo info = (APIInfo)iter.next();
            String className = info.getClassName();
            if (cname != null) {
                if (cname.equals(className)) {
                    iter.remove();
                    continue;
                }
                cname = null;
            }
            if (info.isClass()) {
                cname = info.getName();
            }
        }
    }
}
