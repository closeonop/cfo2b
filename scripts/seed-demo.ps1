# Run only against the local API started with DB_URL ending in /empdb_demo.
# All names, credentials and HR records below are fictional test fixtures.
$ErrorActionPreference = 'Stop'
$demoApi = 'http://localhost:8080/api'
$demoPassword = 'DemoPortal123!'
function Request-Demo($method, $path, $body, $token) {
  $headers = @{}
  if ($token) { $headers.Authorization = "Bearer $token" }
  $request = @{ Method=$method; Uri="$demoApi$path"; Headers=$headers }
  if ($null -ne $body) { $request.ContentType='application/json'; $request.Body=($body | ConvertTo-Json -Depth 10 -Compress) }
  Invoke-RestMethod @request
}
function New-DemoRecord($kind, $employeeId, $data, $token) {
  Request-Demo POST "/hr/records/$kind" @{employeeId=$employeeId;data=$data} $token
}
$demoSession = Request-Demo POST '/auth/onboarding' @{companyName='Northstar Demo (fictional)';email='admin@demo.local';password=$demoPassword} $null
$demoToken=$demoSession.accessToken
$companyId=$demoSession.user.companyId
$null=Request-Demo PUT '/company' @{name='Northstar Demo (fictional)';currency='INR';locale='en-IN';salaryPeriod='MONTHLY';roundingRule='NONE';hrCanSeeSalary=$false} $demoToken
$developer=Request-Demo POST '/job-roles' @{name='Software Developer'} $demoToken
$lead=Request-Demo POST '/job-roles' @{name='Team Lead'} $demoToken
$people=@{}
foreach($person in @(
  @{key='admin';firstName='Alex';lastName='Rivera';team='Operations';salary=90000;position=$lead.id},
  @{key='cfo';firstName='Casey';lastName='Morgan';team='Finance';salary=100000;position=$lead.id},
  @{key='hr';firstName='Harper';lastName='Singh';team='People';salary=65000;position=$lead.id},
  @{key='manager';firstName='Maya';lastName='Sharma';team='Product';salary=85000;position=$lead.id},
  @{key='employee';firstName='Evan';lastName='Patel';team='Product';salary=55000;position=$developer.id},
  @{key='colleague';firstName='Priya';lastName='Desai';team='Product';salary=60000;position=$developer.id}
)) {
  $managerId=if($person.key -in @('employee','colleague')){$people.manager.id}else{$null}
  $people[$person.key]=Request-Demo POST '/employees' @{firstName=$person.firstName;lastName=$person.lastName;email="$($person.key)@demo.local";team=$person.team;startDate='2026-01-05';status='ACTIVE';jobRoleId=$person.position;managerId=$managerId;salary=$person.salary;salaryReason='Fictional demo salary'} $demoToken
}
$null=Request-Demo PUT "/users/$($demoSession.user.id)" @{role='ADMIN';status='ACTIVE';employeeId=$people.admin.id} $demoToken
foreach($key in @('cfo','hr','manager','employee')) {
  $null=Request-Demo POST '/users' @{email="$key@demo.local";password=$demoPassword;role=$key.ToUpper();employeeId=$people[$key].id} $demoToken
}
$employeeSession=Request-Demo POST '/auth/login' @{email='employee@demo.local';password=$demoPassword} $null
$employeeToken=$employeeSession.accessToken
$managerSession=Request-Demo POST '/auth/login' @{email='manager@demo.local';password=$demoPassword} $null
$managerToken=$managerSession.accessToken
$null=New-DemoRecord 'hr-settings' $null @{title='Northstar working hours';timezone='Asia/Kolkata';weeklyHours=40;startTime='09:00';feedbackDays=14;retentionDays=2555} $demoToken
$metric=New-DemoRecord 'metric' $null @{title='Delivery quality';positionId=$developer.id;unit='Percent';target=95;direction='HIGHER'} $demoToken
$null=New-DemoRecord 'metric-value' $people.employee.id @{title='September release checks';metricId=$metric.id;date='2026-09-30';value=97;evidence='Fictional acceptance checks: 97 of 100 passed.'} $demoToken
foreach($reviewDate in @('2026-08-31','2026-09-30')) {
  $scores=@{}; $scores["$($metric.id)"]=if($reviewDate -eq '2026-08-31'){3.5}else{4.2}
  $review=New-DemoRecord 'review' $people.employee.id @{title="Review $reviewDate";dueDate=$reviewDate;status='SHARED';scores=$scores;notes='Fictional review for testing the history and trend.'} $demoToken
}
$null=New-DemoRecord 'review' $people.colleague.id @{title='Upcoming quarterly review';dueDate='2026-10-16';status='SCHEDULED';scores=@{}} $demoToken
$null=New-DemoRecord 'feedback' $people.employee.id @{title='Clear release communication';type='POSITIVE';date='2026-10-07';notes='Kept the fictional team informed about release readiness.'} $managerToken
$null=New-DemoRecord 'feedback' $people.employee.id @{title='Improve handover notes';type='CONSTRUCTIVE';date='2026-10-08';notes='Include a rollback checklist in the next handover.'} $managerToken
$null=New-DemoRecord 'meeting' $people.employee.id @{title='Weekly check-in';date='2026-10-09';expectations='Prepare a complete release handover.';why='Helps the next shift support customers.';feedback='Progress is good; agree the next action.'} $managerToken
$null=New-DemoRecord 'skill' $people.employee.id @{title='TypeScript';level='PROFICIENT'} $employeeToken
$null=New-DemoRecord 'skill' $people.colleague.id @{title='Accessibility';level='EXPERT'} $demoToken
$goal=New-DemoRecord 'goal' $people.employee.id @{title='Own release verification';dueDate='2026-11-15';progress=40;notes='Lead a release with a complete verification checklist.'} $employeeToken
$null=New-DemoRecord 'training' $people.employee.id @{title='Release mentoring';goalId=$goal.id;type='MENTORING';status='IN_PROGRESS'} $employeeToken
$null=New-DemoRecord 'development-request' $people.employee.id @{title='Accessibility workshop';notes='Build skills for inclusive product reviews.';dueDate='2026-11-01'} $employeeToken
$null=New-DemoRecord 'recognition' $people.employee.id @{title='Reliable release support';date='2026-10-09';notes='Supported a fictional cross-team release.'} $managerToken
$null=New-DemoRecord 'reward' $people.employee.id @{title='Quarterly contribution award';type='AWARD';reviewId=$review.id;date='2026-10-10';notes='Fictional award linked to the September review.'} $demoToken
$step=New-DemoRecord 'discipline-step' $null @{title='Coaching conversation';order=1;policy='Document evidence, hear the employee and offer support.'} $demoToken
$null=New-DemoRecord 'discipline-step' $null @{title='Written improvement plan';order=2;policy='Agree a documented improvement plan and review date.'} $demoToken
$null=New-DemoRecord 'discipline' $people.employee.id @{title='Incomplete handover (fictional)';stepId=$step.id;date='2026-10-02';reason='A demo checklist item was missed.';policyViolated='Release handover policy';evidence='Synthetic checklist entry only.';employeeSide='Requested clearer examples.';support='Manager will provide a sample checklist.'} $demoToken
$project=New-DemoRecord 'project' $null @{title='Customer portal refresh';notes='Fictional project for testing task assignments.';dueDate='2026-10-30';status='ACTIVE'} $managerToken
$task=New-DemoRecord 'task' $people.employee.id @{title='Prepare release checklist';projectId=$project.id;dueDate='2026-10-11';why='Make support handovers predictable.';impact='Release sign-off may be delayed.';status='IN_PROGRESS'} $managerToken
$null=New-DemoRecord 'task' $people.colleague.id @{title='Review accessibility notes';projectId=$project.id;dueDate='2026-10-09';why='Make navigation usable with a keyboard.';impact='Blocks the final design review.';dependsOnId=$task.id;status='TODO'} $managerToken
$null=New-DemoRecord 'task' $people.employee.id @{title='Update screenshots';projectId=$project.id;dueDate='2026-10-20';why='Help the team review the new navigation.';impact='Documentation will remain outdated.';status='TODO'} $managerToken
foreach($key in @('employee','colleague','manager','hr','cfo','admin')) { $null=New-DemoRecord 'leave-balance' $people[$key].id @{title='Annual';year=2026;days=20} $demoToken }
$null=New-DemoRecord 'leave-request' $people.employee.id @{title='Annual';startDate='2026-10-19';endDate='2026-10-20';notes='Fictional personal leave request.'} $employeeToken
$null=New-DemoRecord 'profile-request' $people.employee.id @{title='Update demo contact number';phone='555-0100'} $employeeToken
$null=New-DemoRecord 'help-request' $people.employee.id @{title='Where is the leave policy?';category='LEAVE';notes='Please explain the fictional leave approval process.'} $employeeToken
$null=New-DemoRecord 'policy' $null @{title='Demo code of conduct';notes='Treat colleagues with respect. This is synthetic test content, not a legal policy.'} $demoToken
$job=New-DemoRecord 'job-posting' $null @{title='Frontend developer';positionId=$developer.id;description='Fictional vacancy for hiring workflow tests.';status='OPEN'} $demoToken
$candidate=New-DemoRecord 'candidate' $null @{title='Jordan Demo';email='candidate@demo.local';jobId=$job.id;status='INTERVIEW';notes='Fictional candidate.'} $demoToken
$null=New-DemoRecord 'interview' $null @{title='Frontend scorecard';candidateId=$candidate.id;date='2026-10-12';score=4;notes='Synthetic evidence from a demo exercise.'} $demoToken
$null=New-DemoRecord 'onboarding' $people.employee.id @{title='Read the demo policies';assignedTo='EMPLOYEE';dueDate='2026-10-15';status='TODO'} $demoToken
$null=New-DemoRecord 'onboarding' $people.employee.id @{title='Agree the first-week plan';assignedTo='MANAGER';dueDate='2026-10-15';status='TODO'} $demoToken
# Fixture-only backdated time entries allow testing approval/export immediately.
foreach($offset in 0..4) {
  $date=([datetime]'2026-09-28').AddDays($offset).ToString('yyyy-MM-dd')
  $sql="INSERT INTO attendance_entries(company_id,employee_id,task_id,clock_in,clock_out) VALUES ($companyId,$($people.employee.id),$($task.id),'$date 03:30:00+00','$date 11:30:00+00');"
  $null=docker exec emp-db psql -U emp -d empdb_demo -v ON_ERROR_STOP=1 -c $sql
  if($LASTEXITCODE -ne 0){throw 'Could not seed demo attendance.'}
}
$null=Request-Demo POST '/attendance/submit' @{weekStart='2026-09-28'} $employeeToken
Write-Output 'Demo ready: six fictional employees, five role accounts, lifecycle records and a 40-hour timesheet awaiting approval.'
