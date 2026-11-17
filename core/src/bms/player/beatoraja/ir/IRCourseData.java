package bms.player.beatoraja.ir;

import bms.player.beatoraja.CourseData;
import bms.player.beatoraja.CourseData.CourseDataConstraint;

/**
 * IR用コースデータ
 * 
 * @author exch
 */
public class IRCourseData {

    /**
     * コース名
     */
    public final String name;
    /**
     * 楽曲のハッシュ
     */
    public final IRChartData[] charts;
    /**
     * コースの制限
     */
    public final CourseDataConstraint[] constraint;
	/**
	 * トロフィーデータ
	 */
	public final IRTrophyData[] trophy;
	/**
	 * LN TYPE(-1 : 未指定, 0: LN, 1: CN, 2: HCN, 3: HLN, 4: CN+, 5: HCN+)
	 */
	public final int lntype;
	/**
	 * 疑似BSS/MSS変換オプションが適用されたコースプレイかどうか
	 */
	public final boolean pseudoBss;

	public IRCourseData(String name, IRChartData[] charts, CourseDataConstraint[] constraint, IRTrophyData[] trophy,
			int lntype) {
		this(name, charts, constraint, trophy, lntype, IRScoreData.isPseudoBssLntype(lntype));
	}

	public IRCourseData(String name, IRChartData[] charts, CourseDataConstraint[] constraint, IRTrophyData[] trophy,
			int lntype, boolean pseudoBss) {
		this.name = name;
		this.charts = charts != null ? charts : new IRChartData[0];
		this.constraint = constraint != null ? constraint : new CourseDataConstraint[0];
		this.trophy = trophy != null ? trophy : new IRTrophyData[0];
		this.lntype = IRScoreData.toIrLntype(lntype, pseudoBss);
		this.pseudoBss = pseudoBss || IRScoreData.isPseudoBssLntype(this.lntype);
	}

    public IRCourseData(CourseData course) {
    	this(course, -1, false);
    }

    public IRCourseData(CourseData course, int lntype) {
    	this(course, lntype, IRScoreData.isPseudoBssLntype(lntype));
    }

    public IRCourseData(CourseData course, int lntype, boolean pseudoBss) {
    	this.name = course.getName();
    	this.charts = new IRChartData[course.getSong().length];
    	for(int i = 0;i < this.charts.length;i++) {
    		charts[i] = new IRChartData(course.getSong()[i]);
    	}
    	this.constraint = new CourseDataConstraint[course.getConstraint().length];
    	for(int i = 0;i < this.constraint.length;i++) {
    		constraint[i] =course.getConstraint()[i];
    	}
    	this.lntype = IRScoreData.toIrLntype(lntype, pseudoBss);
    	this.pseudoBss = pseudoBss || IRScoreData.isPseudoBssLntype(this.lntype);

    	this.trophy = new IRTrophyData[course.getTrophy().length];
    	for(int i = 0; i < trophy.length;i++) {
    		trophy[i] = new IRTrophyData(course.getTrophy()[i]);
		}
    }

	/**
	 * IR用トロフィーデータ
	 *
	 * @author exch
	 */
	public static class IRTrophyData {
		/**
		 * トロフィー名称
		 */
    	public final String name;
		/**
		 * トロフィーのスコアレート条件
		 */
		public final float scorerate;
		/**
		 * トロフィーのミスレート条件
		 */
		public final float smissrate;

    	public IRTrophyData (CourseData.TrophyData trophy) {
    		name = trophy.getName();
    		scorerate = trophy.getScorerate();
			smissrate = trophy.getMissrate();
		}

		public IRTrophyData(String name, float scorerate, float smissrate) {
			this.name = name;
			this.scorerate = scorerate;
			this.smissrate = smissrate;
		}
	}
}
