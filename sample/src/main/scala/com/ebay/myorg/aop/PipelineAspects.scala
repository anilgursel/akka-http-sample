package com.ebay.myorg.aop

import com.ebay.squbs.rocksqubs.cal.ctx.{CalContext, CalScopeAware}
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation._

/**
 * Created by lma on 14-7-22.
 */
@Aspect
class PipelineAspects {

  @DeclareMixin("com.ebay.myorg.RequestContext")
  def mixinCalScopeToRequestContext: CalScopeAware = CalScopeAware.default

  @Pointcut("execution(com.ebay.myorg.RequestContext.new(..)) && this(ctx)")
  def requestContextCreation(ctx: CalScopeAware): Unit = {}


  @After("requestContextCreation(ctx)")
  def afterRequestContextCreation(ctx: CalScopeAware): Unit = {
    // Force traceContext initialization.
    ctx.calScope
  }

  @Pointcut("execution(* com.ebay.myorg.RequestContext.copy(..)) && this(old)")
  def copyingRequestContext(old: CalScopeAware): Unit = {}

  @Around("copyingRequestContext(old)")
  def aroundCopyingRequestContext(pjp: ProceedingJoinPoint, old: CalScopeAware): Any = {
    if (CalContext.current == null)
      CalContext.withContext(old.calScope) {
        pjp.proceed()
      }
    else pjp.proceed()
  }

  //below can be replaced by static interception

//  @Pointcut("execution(* com.ebay.myorg.SyncHandler.handle(..)) && args(requestContext)")
//  def syncHandle(requestContext: CalScopeAware): Unit = {}
//
//  @Around("syncHandle(requestContext)")
//  def aroundSyncHandle(pjp: ProceedingJoinPoint, requestContext: CalScopeAware) = {
//    CalContext.withContext(requestContext.calScope) {
//      pjp.proceed()
//    }
//  }
//
//  @Pointcut("execution(* com.ebay.myorg.AsyncHandler.handle(..)) && args(requestContext)")
//  def asyncHandle(requestContext: CalScopeAware): Unit = {}
//
//  @Around("asyncHandle(requestContext)")
//  def aroundAsyncHandle(pjp: ProceedingJoinPoint, requestContext: CalScopeAware) = {
//    CalContext.withContext(requestContext.calScope) {
//      pjp.proceed()
//    }
//  }


}
