package com.intellij.slicer;

import com.intellij.analysis.AnalysisScope;
import com.intellij.analysis.AnalysisUIOptions;
import com.intellij.analysis.BaseAnalysisActionDialog;
import com.intellij.ide.impl.ContentManagerWatcher;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationAdapter;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.impl.ProgressManagerImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.*;
import com.intellij.refactoring.util.RefactoringDescriptionLocation;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.usageView.UsageInfo;
import org.jetbrains.annotations.NotNull;

public class SliceManager {
  private final Project myProject;
  private final ContentManager myContentManager;
  private static final String TOOL_WINDOW_ID = "Dataflow to this";
  private volatile boolean myCanceled;

  public static SliceManager getInstance(@NotNull Project project) {
    return ServiceManager.getService(project, SliceManager.class);
  }

  public SliceManager(Project project, ToolWindowManager toolWindowManager, final Application application) {
    myProject = project;
    final ToolWindow toolWindow= toolWindowManager.registerToolWindow(TOOL_WINDOW_ID, true, ToolWindowAnchor.BOTTOM, project);
    myContentManager = toolWindow.getContentManager();
    new ContentManagerWatcher(toolWindow, myContentManager);
    final MyApplicationListener myApplicationListener = new MyApplicationListener();
    application.addApplicationListener(myApplicationListener);
    Disposer.register(project, new Disposable() {
      public void dispose() {
        application.removeApplicationListener(myApplicationListener);
      }
    });
  }

  public void slice(@NotNull PsiElement element) {
    Module module = ModuleUtil.findModuleForPsiElement(element);
    AnalysisUIOptions analysisUIOptions = new AnalysisUIOptions();
    analysisUIOptions.SCOPE_TYPE = AnalysisScope.PROJECT;
    BaseAnalysisActionDialog dialog = new BaseAnalysisActionDialog("Dataflow to this", "Analyze scope", myProject, new AnalysisScope(element.getContainingFile()), module.getName(), true,
                                                                   analysisUIOptions);
    dialog.show();
    if (!dialog.isOK()) return;
    AnalysisScope scope = dialog.getScope(analysisUIOptions, new AnalysisScope(myProject), myProject, module);

    final SliceToolwindowSettings sliceToolwindowSettings = SliceToolwindowSettings.getInstance(myProject);
    SliceUsage usage = createRootUsage(element, scope);
    final Content[] myContent = new Content[1];
    final SlicePanel slicePanel = new SlicePanel(myProject, usage, scope) {
      protected void close() {
        myContentManager.removeContent(myContent[0], true);
      }

      public boolean isAutoScroll() {
        return sliceToolwindowSettings.isAutoScroll();
      }

      public void setAutoScroll(boolean autoScroll) {
        sliceToolwindowSettings.setAutoScroll(autoScroll);
      }

      public boolean isPreview() {
        return sliceToolwindowSettings.isPreview();
      }

      public void setPreview(boolean preview) {
        sliceToolwindowSettings.setPreview(preview);
      }
    };

    String title = getElementDescription(element);
    myContent[0] = myContentManager.getFactory().createContent(slicePanel, title, true);
    myContentManager.addContent(myContent[0]);
    myContentManager.setSelectedContent(myContent[0]);

    ToolWindowManager.getInstance(myProject).getToolWindow(TOOL_WINDOW_ID).activate(null);
  }

  public static String getElementDescription(PsiElement element) {
    PsiElement elementToSlice = element;
    if (element instanceof PsiReferenceExpression) elementToSlice = ((PsiReferenceExpression)element).resolve();
    if (elementToSlice == null) elementToSlice = element;
    String title = "<html>"+
                   ElementDescriptionUtil.getElementDescription(elementToSlice, RefactoringDescriptionLocation.WITHOUT_PARENT);
    title = StringUtil.first(title, 100, true)+"</html>";
    return title;
  }

  public static SliceUsage createRootUsage(@NotNull PsiElement element, @NotNull AnalysisScope scope) {
    UsageInfo usageInfo = new UsageInfo(element);
    SliceUsage usage;
    if (element instanceof PsiField) {
      usage = new SliceFieldUsage(usageInfo, (PsiField)element, scope);
    }
    else if (element instanceof PsiParameter) {
      usage = new SliceParameterUsage(usageInfo, (PsiParameter)element, scope);
    }
    else {
      usage = new SliceUsage(usageInfo, scope);
    }
    return usage;
  }

  private class MyApplicationListener extends ApplicationAdapter {
    public void beforeWriteActionStart(Object action) {
      myCanceled = true;
    }
  }

  public void checkCanceled() throws ProcessCanceledException {
    if (myCanceled) {
      throw new ProcessCanceledException();
    }
  }

  public void runInterruptibly(Runnable runnable, Runnable onCancel, ProgressIndicator progress) throws ProcessCanceledException {
    myCanceled = false;
    try {
      progress.checkCanceled();
      ((ProgressManagerImpl)ProgressManager.getInstance()).executeProcessUnderProgress(runnable, progress);
    }
    catch (ProcessCanceledException e) {
      myCanceled = true;
      progress.cancel();
      //reschedule for later
      onCancel.run();
      throw e;
    }
  }
}
