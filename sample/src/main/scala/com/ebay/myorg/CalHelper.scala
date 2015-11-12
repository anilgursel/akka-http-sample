package com.ebay.myorg

import com.ebay.squbs.rocksqubs.cal.ctx.{CalContext, CalScopeAware}

/**
 * Created by lma on 11/12/2015.
 */
object CalHelper {
  def cal[T](rc: CalScopeAware, f: => T): T = {
    CalContext.withContext(rc.calScope) {
      f
    }
  }
}
